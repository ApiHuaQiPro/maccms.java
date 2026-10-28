package com.maccms.backend.integration.http;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.LogRedactor;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一出站客户端（B23，路线图第 4.3 节 ④；26 文档第 3 节）：{@code integration} 已登记子包的 HTTP 请求都经过这里。
 *
 * <ul>
 * <li>调用方检查：直接调用方（本包之外的第一个栈帧）所在的包必须在 {@link IntegrationPackages#REGISTERED} 中，否则拒绝；</li>
 * <li>目标检查：{@link OutboundPolicy}，每一跳都查（测试配置下只允许回环地址）；</li>
 * <li>超时：连接超时与"发出请求到读完响应"的总时限；响应体超过上限即中止；</li>
 * <li>跳转：与旧 {@code mac_curl_*}（{@code CURLOPT_FOLLOWLOCATION}）一样跟随，最多 {@link #MAX_REDIRECTS} 次；
 * 301/302/303 后 POST 改为不带请求体的 GET（同 curl）；换主机时不带 {@code Authorization}/{@code Cookie}；</li>
 * <li>日志：只记用途、方法、目标主机、状态码、字节数与耗时，不记路径、查询串、请求头与请求体；非 2xx 时另记一段
 * 经 {@link LogRedactor} 脱敏（含配置中的已知密钥）的响应摘要；异常只记类型链。</li>
 * </ul>
 * 用户代理沿用旧版 curl 设置的字符串。与旧版的差异：校验 TLS 证书（旧版 {@code CURLOPT_SSL_VERIFYPEER=0}）。
 */
public final class OutboundHttpClient {

    /** 旧 {@code mac_curl_get}/{@code mac_curl_post} 的 {@code CURLOPT_USERAGENT}（application/common.php:579,610）。 */
    static final String LEGACY_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/45.0.2454.101 Safari/537.36";
    static final int MAX_REDIRECTS = 5;
    static final int LOG_EXCERPT_BYTES = 1024;

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpClient.class);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String OWN_PACKAGE = OutboundHttpClient.class.getPackageName();
    private static final Set<String> CROSS_HOST_DROPPED = Set.of("authorization", "cookie", "proxy-authorization");

    private final OutboundPolicy policy;
    private final Set<String> registered;
    private final Duration readTimeout;
    private final int maxResponseBytes;
    private final List<String> knownSecrets;
    private final HttpClient http;

    /**
     * @param registered   允许调用的子包（相对 {@code com.maccms.backend}），生产为 {@link IntegrationPackages#REGISTERED} 的键
     * @param knownSecrets 配置中的密钥值，写日志前按原文遮盖
     */
    public OutboundHttpClient(OutboundPolicy policy, Collection<String> registered, Duration connectTimeout,
                              Duration readTimeout, int maxResponseBytes, Collection<String> knownSecrets) {
        this.policy = policy;
        this.registered = Set.copyOf(registered);
        this.readTimeout = readTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.knownSecrets = List.copyOf(knownSecrets);
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public OutboundPolicy.Mode mode() {
        return policy.mode();
    }

    /**
     * 发出请求并读完响应（任何状态码都正常返回，由调用方按第三方协议解释）。
     *
     * @throws OutboundException 调用方未登记、目标被拒绝、连接失败、超时、响应过大、跳转失败等
     */
    public OutboundResponse send(OutboundRequest request) {
        String caller = checkCaller();
        long start = System.nanoTime();
        long deadline = start + readTimeout.toNanos();
        String method = request.method();
        URI uri = request.uri();
        byte[] body = request.body();
        Map<String, List<String>> headers = request.headers();
        String host = policy.check(uri);
        for (int hop = 0; ; hop++) {
            HttpResponse<byte[]> resp = exchange(method, uri, headers, body, host, deadline);
            int status = resp.statusCode();
            Optional<String> location = resp.headers().firstValue("location");
            if (isRedirect(status) && location.isPresent()) {
                if (hop >= MAX_REDIRECTS) {
                    throw new OutboundException(OutboundException.Reason.REDIRECT_FAILED, host);
                }
                URI next;
                try {
                    next = uri.resolve(location.get().strip());
                } catch (IllegalArgumentException e) {
                    throw new OutboundException(OutboundException.Reason.REDIRECT_FAILED, host);
                }
                String nextHost = policy.check(next);
                if (status == 303 || ((status == 301 || status == 302) && method.equals("POST"))) {
                    method = "GET";
                    body = null;
                    headers = withoutContentType(headers);
                }
                if (!sameOrigin(uri, next)) {
                    headers = withoutCredentials(headers);
                }
                log.info("outbound redirect purpose={} status={} from={} to={}", request.purpose(), status, host, nextHost);
                uri = next;
                host = nextHost;
                continue;
            }
            byte[] data = resp.body();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (status >= 200 && status < 300) {
                log.info("outbound purpose={} caller={} method={} host={} status={} bytes={} ms={}",
                        request.purpose(), caller, method, host, status, data.length, ms);
                if (log.isDebugEnabled()) {
                    log.debug("outbound response purpose={} host={} body={}", request.purpose(), host, excerpt(data));
                }
            } else {
                log.warn("outbound purpose={} caller={} method={} host={} status={} bytes={} ms={} body={}",
                        request.purpose(), caller, method, host, status, data.length, ms, excerpt(data));
            }
            return new OutboundResponse(status, lowerCaseHeaders(resp.headers().map()), data, host);
        }
    }

    /** 脱敏后的响应摘要（前 {@link #LOG_EXCERPT_BYTES} 字节按 UTF-8 解码）。 */
    private String excerpt(byte[] data) {
        int n = Math.min(data.length, LOG_EXCERPT_BYTES);
        return LogRedactor.redact(new String(data, 0, n, StandardCharsets.UTF_8), knownSecrets);
    }

    private HttpResponse<byte[]> exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body,
                                          String host, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw fail(OutboundException.Reason.TIMEOUT, host, "timeout", null);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(remaining))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        boolean hasAgent = false;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            hasAgent |= e.getKey().equalsIgnoreCase("user-agent");
            for (String v : e.getValue()) {
                b.header(e.getKey(), v);
            }
        }
        if (!hasAgent) {
            b.header("User-Agent", LEGACY_USER_AGENT);
        }
        CompletableFuture<HttpResponse<byte[]>> f = http.sendAsync(b.build(), info -> new LimitedBody(maxResponseBytes));
        try {
            return f.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw fail(OutboundException.Reason.TIMEOUT, host, "timeout", e);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw fail(OutboundException.Reason.INTERRUPTED, host, "interrupted", e);
        } catch (CancellationException e) {
            throw fail(OutboundException.Reason.IO_ERROR, host, "cancelled", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            OutboundException.Reason reason;
            if (c instanceof OutboundException oe) {
                reason = oe.reason();
            } else if (c instanceof HttpConnectTimeoutException || c instanceof HttpTimeoutException) {
                reason = OutboundException.Reason.TIMEOUT;
            } else if (c instanceof ConnectException) {
                reason = OutboundException.Reason.CONNECT_FAILED;
            } else {
                reason = OutboundException.Reason.IO_ERROR;
            }
            throw fail(reason, host, reason.name().toLowerCase(Locale.ROOT), c);
        }
    }

    private static OutboundException fail(OutboundException.Reason reason, String host, String what, Throwable cause) {
        OutboundException e = new OutboundException(reason, host, cause);
        // 只记类型链：cause 的 message 不进日志
        log.warn("outbound failed reason={} host={} error={}", what, host,
                cause == null ? "-" : SafeExceptionLog.typeChain(cause));
        return e;
    }

    /** 直接调用方（本包之外的第一个栈帧）所在的包必须已登记；返回其类名（用于日志）。 */
    private String checkCaller() {
        Class<?> caller = WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getPackageName().equals(OWN_PACKAGE))
                .findFirst().orElse(null));
        String pkg = caller == null ? null : IntegrationPackages.modulePackage(caller.getPackageName());
        if (!IntegrationPackages.isRegistered(pkg, registered)) {
            log.warn("outbound rejected: caller not registered caller={}", caller == null ? "-" : caller.getName());
            throw new OutboundException(OutboundException.Reason.CALLER_NOT_REGISTERED, "");
        }
        return caller.getName();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI u) {
        return u.getPort() >= 0 ? u.getPort() : u.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static Map<String, List<String>> withoutCredentials(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (!CROSS_HOST_DROPPED.contains(k.toLowerCase(Locale.ROOT))) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, List<String>> withoutContentType(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (!k.equalsIgnoreCase("content-type")) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, List<String>> lowerCaseHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.merge(k.toLowerCase(Locale.ROOT), List.copyOf(v),
                (x, y) -> java.util.stream.Stream.concat(x.stream(), y.stream()).toList()));
        return out;
    }

    /** 累积响应体，超过上限即取消订阅并以 {@link OutboundException.Reason#RESPONSE_TOO_LARGE} 结束。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                if (buf.size() + b.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new OutboundException(OutboundException.Reason.RESPONSE_TOO_LARGE, ""));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                buf.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(buf.toByteArray());
        }
    }
}
