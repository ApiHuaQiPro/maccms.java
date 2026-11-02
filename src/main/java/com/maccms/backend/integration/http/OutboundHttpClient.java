package com.maccms.backend.integration.http;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.LogRedactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
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
 *
 * <p>内部引擎（APPK-2 OUT-1-5，DEC-14 / NET-7）：Apache HttpClient 5 classic，只在本包内使用。每一跳新建只含一条连接的
 * 连接管理器，由 {@link PinnedDnsResolver} 只返回 {@link OutboundPolicy#check} 已检查的地址，建连时不再按主机名解析；
 * {@code Host} 头、TLS 的 SNI 与证书主机名校验仍用原主机名；路由固定直连，套接字以 {@link Proxy#NO_PROXY} 创建，不读系统代理。
 * 连接不跨跳、不跨请求复用（与旧版每次新建 curl 句柄相同）。不发 {@code Accept-Encoding}、不自动解压、不自动重试、不存 cookie。
 *
 * <p>总时限由本实例持有的单个守护线程定时器执行：到点对进行中的请求 {@code cancel()}。定时器线程空闲
 * {@link #TIMER_IDLE_SECONDS} 秒后自行退出、有请求时再建，所以应用停止时不需要单独关闭（守护线程也不阻止 JVM 退出）。
 * 每跳结束（成功或异常）依次：撤销定时任务 → 中止交换（连接不复用，关闭时不再读剩余响应体）→ 关闭响应 → 关闭客户端
 * → 关闭连接管理器。
 */
public final class OutboundHttpClient {

    /** 旧 {@code mac_curl_get}/{@code mac_curl_post} 的 {@code CURLOPT_USERAGENT}（application/common.php:579,610）。 */
    static final String LEGACY_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/45.0.2454.101 Safari/537.36";
    static final int MAX_REDIRECTS = 5;
    static final int LOG_EXCERPT_BYTES = 1024;
    static final int TIMER_IDLE_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpClient.class);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String OWN_PACKAGE = OutboundHttpClient.class.getPackageName();
    private static final Set<String> CROSS_HOST_DROPPED = Set.of("authorization", "cookie", "proxy-authorization");
    /** 调用方不能设置的请求头（同 JDK {@code HttpClient} 的受限头，NET-7 ⑤）。 */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "connection", "content-length", "expect", "upgrade");
    /** 直连套接字：{@code new Socket()} 连接时会询问系统 {@code ProxySelector}（SOCKS），这里显式不用代理。 */
    private static final DetachedSocketFactory DIRECT_SOCKETS = proxy -> new Socket(Proxy.NO_PROXY);

    private final OutboundPolicy policy;
    private final Set<String> registered;
    private final Duration readTimeout;
    private final int maxResponseBytes;
    private final List<String> knownSecrets;
    private final ConnectionConfig connectionConfig;
    private final Lookup<TlsSocketStrategy> tls;
    private final ScheduledThreadPoolExecutor deadlines;

    /**
     * @param registered   允许调用的子包（相对 {@code com.maccms.backend}），生产为 {@link IntegrationPackages#REGISTERED} 的键
     * @param knownSecrets 配置中的密钥值，写日志前按原文遮盖
     */
    public OutboundHttpClient(OutboundPolicy policy, Collection<String> registered, Duration connectTimeout,
                              Duration readTimeout, int maxResponseBytes, Collection<String> knownSecrets) {
        this(policy, registered, connectTimeout, readTimeout, maxResponseBytes, knownSecrets, defaultSslContext());
    }

    /** 同公开构造器，TLS 用给定的 {@link SSLContext}（只供 V-33 的 TLS 用例信任测试证书；主机名校验同样开启）。 */
    OutboundHttpClient(OutboundPolicy policy, Collection<String> registered, Duration connectTimeout,
                       Duration readTimeout, int maxResponseBytes, Collection<String> knownSecrets, SSLContext sslContext) {
        this.policy = policy;
        this.registered = Set.copyOf(registered);
        this.readTimeout = readTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.knownSecrets = List.copyOf(knownSecrets);
        this.connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .build();
        this.tls = RegistryBuilder.<TlsSocketStrategy>create()
                .register("https", new DefaultClientTlsStrategy(sslContext))
                .build();
        this.deadlines = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "outbound-deadline");
            t.setDaemon(true);
            return t;
        });
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setKeepAliveTime(TIMER_IDLE_SECONDS, TimeUnit.SECONDS);
        deadlines.allowCoreThreadTimeOut(true);
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 默认 TLS 不可用", e);
        }
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
        return send(request, maxResponseBytes);
    }

    /**
     * 同 {@link #send(OutboundRequest)}，响应体上限按本次请求给出（B29 插件包下载：包的大小上限独立于第三方接口的通用上限）。
     *
     * @param maxResponseBytes 本次请求的响应体上限，必须大于 0
     */
    public OutboundResponse send(OutboundRequest request, int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes 必须大于 0");
        }
        String caller = checkCaller();
        long start = System.nanoTime();
        long deadline = start + readTimeout.toNanos();
        String method = request.method();
        URI uri = request.uri();
        byte[] body = request.body();
        Map<String, List<String>> headers = request.headers();
        OutboundPolicy.Target target = policy.check(uri);
        String host = target.logHost();
        for (int hop = 0; ; hop++) {
            Exchanged resp = exchange(method, uri, headers, body, target, deadline, maxResponseBytes);
            int status = resp.status();
            List<String> location = resp.headers().getOrDefault("location", List.of());
            if (isRedirect(status) && !location.isEmpty()) {
                if (hop >= MAX_REDIRECTS) {
                    throw new OutboundException(OutboundException.Reason.REDIRECT_FAILED, host);
                }
                URI next;
                try {
                    next = uri.resolve(location.get(0).strip());
                } catch (IllegalArgumentException e) {
                    throw new OutboundException(OutboundException.Reason.REDIRECT_FAILED, host);
                }
                OutboundPolicy.Target nextTarget = policy.check(next);
                String nextHost = nextTarget.logHost();
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
                target = nextTarget;
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
            return new OutboundResponse(status, resp.headers(), data, host);
        }
    }

    /** 脱敏后的响应摘要（前 {@link #LOG_EXCERPT_BYTES} 字节按 UTF-8 解码）。 */
    private String excerpt(byte[] data) {
        int n = Math.min(data.length, LOG_EXCERPT_BYTES);
        return LogRedactor.redact(new String(data, 0, n, StandardCharsets.UTF_8), knownSecrets);
    }

    /** 一跳的结果：状态码、按 {@link #lowerCaseHeaders} 合并的响应头、响应体。 */
    private record Exchanged(int status, Map<String, List<String>> headers, byte[] body) {
    }

    /** 一跳（NET-7 ②～⑥）：用这一跳已检查的地址建连，发出请求并读完响应；跳结束即关闭全部资源，连接不复用。 */
    private Exchanged exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body,
                               OutboundPolicy.Target target, long deadline, int maxResponseBytes) {
        String host = target.logHost();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw fail(OutboundException.Reason.TIMEOUT, host, "timeout", null);
        }
        HttpUriRequestBase req = new HttpUriRequestBase(method, uri);
        boolean hasAgent = false;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if (RESTRICTED_HEADERS.contains(name)) {
                throw new IllegalArgumentException("restricted header name: \"" + e.getKey() + "\"");
            }
            hasAgent |= name.equals("user-agent");
            for (String v : e.getValue()) {
                req.addHeader(e.getKey(), v);
            }
        }
        if (!hasAgent) {
            req.addHeader("User-Agent", LEGACY_USER_AGENT);
        }
        if (body != null) {
            // Content-Type 原样随上面复制的请求头发出（实体不另带类型，不改写调用方给的值）；POST/PUT 无请求体时引擎发 Content-Length: 0
            req.setEntity(new ByteArrayEntity(body, null));
        }
        if (Thread.currentThread().isInterrupted()) {
            throw fail(OutboundException.Reason.INTERRUPTED, host, "interrupted", null);
        }
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS))
                .setRedirectsEnabled(false)
                .build());
        String hostName = uri.getHost();
        if (hostName.startsWith("[") && hostName.endsWith("]")) {
            hostName = hostName.substring(1, hostName.length() - 1);
        }
        // 原 scheme、原主机名、原端口：Host 头、SNI 与证书主机名校验都用它；地址只来自 PinnedDnsResolver
        HttpHost endpoint = new HttpHost(uri.getScheme().toLowerCase(Locale.ROOT), hostName, uri.getPort());

        BasicHttpClientConnectionManager connections = new BasicHttpClientConnectionManager(
                new DefaultHttpClientConnectionOperator(DIRECT_SOCKETS, DefaultSchemePortResolver.INSTANCE,
                        new PinnedDnsResolver(target.host(), target.addresses()), tls), null);
        connections.setConnectionConfig(connectionConfig);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = null;
        CloseableHttpClient http = null;
        ClassicHttpResponse resp = null;
        try {
            http = HttpClients.custom()
                    .setConnectionManager(connections)
                    .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .disableCookieManagement()
                    .disableAuthCaching()
                    .disableContentCompression()
                    .disableDefaultUserAgent()
                    // 引擎默认补的 Connection: keep-alive 去掉：基线 JDK 客户端不发这个头（HTTP/1.1 默认即持久连接，这里也不复用）
                    .addRequestInterceptorLast((r, entity, ctx) -> r.removeHeaders("Connection"))
                    .build();
            timer = deadlines.schedule(() -> {
                timedOut.set(true);
                req.cancel();
            }, remaining, TimeUnit.NANOSECONDS);
            resp = http.executeOpen(endpoint, req, context);
            byte[] data = readLimited(resp.getEntity(), maxResponseBytes, req);
            Map<String, List<String>> raw = new LinkedHashMap<>();
            for (Header h : resp.getHeaders()) {
                raw.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
            }
            return new Exchanged(resp.getCode(), lowerCaseHeaders(raw), data);
        } catch (OutboundException e) {
            throw fail(e.reason(), host, e.reason().name().toLowerCase(Locale.ROOT), e);
        } catch (IOException e) {
            OutboundException.Reason reason = classify(e, timedOut.get());
            throw fail(reason, host, reason.name().toLowerCase(Locale.ROOT), e);
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
            req.cancel();
            closeQuietly(resp);
            closeQuietly(http);
            connections.close(CloseMode.IMMEDIATE);
        }
    }

    /** NET-7 ⑥：引擎异常归入既有原因。被定时取消的一律算超时；线程中断（中断标志保留）算中断。 */
    private static OutboundException.Reason classify(IOException e, boolean timedOut) {
        if (timedOut || e instanceof ConnectTimeoutException || e instanceof SocketTimeoutException) {
            return OutboundException.Reason.TIMEOUT;
        }
        if (Thread.currentThread().isInterrupted()) {
            return OutboundException.Reason.INTERRUPTED;
        }
        if (e instanceof HttpHostConnectException || e instanceof ConnectException
                || e instanceof NoRouteToHostException || e instanceof UnknownHostException) {
            return OutboundException.Reason.CONNECT_FAILED;
        }
        return OutboundException.Reason.IO_ERROR;
    }

    /** 边读边计数，超过上限立即中止连接（不信任 {@code Content-Length}，也不读剩余部分）。 */
    private static byte[] readLimited(HttpEntity entity, int max, HttpUriRequestBase req) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = entity.getContent();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1; ) {
            if (buf.size() + n > max) {
                req.cancel();
                throw new OutboundException(OutboundException.Reason.RESPONSE_TOO_LARGE, "");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // 交换已中止，关闭失败不影响结果
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
}
