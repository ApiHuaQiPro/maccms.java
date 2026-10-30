package com.maccms.backend.integration.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 本机模拟第三方服务（B23，路线图第 4.3 节 ②；26 文档第 6 节）：只绑定 {@code 127.0.0.1} 的随机端口，供 B24～B29 在测试里
 * 模拟支付、短信、OAuth、推送等 HTTP 接口。按"方法 + 路径"登记桩响应，记录收到的每个请求；未登记的路径返回 404。
 *
 * <pre>{@code
 * try (LoopbackMockServer mock = LoopbackMockServer.start()) {
 *     mock.stub("POST", "/gateway.do", MockResponse.json(200, "{\"code\":\"10000\"}"));
 *     client.send(OutboundRequest.post("pay.test", mock.uri("/gateway.do"), "application/json", body));
 *     assertThat(mock.requests()).singleElement().satisfies(r -> assertThat(r.query()).contains("sign="));
 * }
 * }</pre>
 * 只用于测试；测试期出站护栏（{@link TestOutboundGuard}）保证测试只能连到这类回环服务。
 */
public final class LoopbackMockServer implements AutoCloseable {

    /** 收到的请求。头名称小写。 */
    public record RecordedRequest(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    /** 桩响应。{@code delay} 在写响应前等待（模拟超时）。 */
    public record MockResponse(int status, Map<String, String> headers, byte[] body, Duration delay) {

        public static MockResponse of(int status, String contentType, String body) {
            return new MockResponse(status, Map.of("Content-Type", contentType), body.getBytes(StandardCharsets.UTF_8),
                    Duration.ZERO);
        }

        public static MockResponse json(int status, String body) {
            return of(status, "application/json; charset=utf-8", body);
        }

        public static MockResponse text(int status, String body) {
            return of(status, "text/plain; charset=utf-8", body);
        }

        public static MockResponse redirect(int status, String location) {
            return new MockResponse(status, Map.of("Location", location), new byte[0], Duration.ZERO);
        }

        public MockResponse withDelay(Duration d) {
            return new MockResponse(status, headers, body, d);
        }

        public MockResponse withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new MockResponse(status, h, body, delay);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Function<RecordedRequest, MockResponse>> stubs = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private LoopbackMockServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** 在 127.0.0.1 的随机端口上启动。 */
    public static LoopbackMockServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService ex = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "loopback-mock");
                t.setDaemon(true);
                return t;
            });
            LoopbackMockServer mock = new LoopbackMockServer(s, ex);
            s.createContext("/", mock::handle);
            s.setExecutor(ex);
            s.start();
            return mock;
        } catch (IOException e) {
            throw new IllegalStateException("模拟服务启动失败", e);
        }
    }

    /** 登记固定响应。 */
    public LoopbackMockServer stub(String method, String path, MockResponse response) {
        return stub(method, path, r -> response);
    }

    /** 登记按请求计算的响应（如校验签名后决定返回什么）。 */
    public LoopbackMockServer stub(String method, String path, Function<RecordedRequest, MockResponse> handler) {
        stubs.put(method.toUpperCase(Locale.ROOT) + " " + path, handler);
        return this;
    }

    /** 本服务上的地址，如 {@code http://127.0.0.1:54321/path}。 */
    public URI uri(String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + port() + pathAndQuery);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** 迄今收到的请求（按到达顺序）。 */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
            URI u = ex.getRequestURI();
            RecordedRequest req = new RecordedRequest(ex.getRequestMethod(), u.getRawPath(),
                    u.getRawQuery() == null ? "" : u.getRawQuery(), headers, body);
            requests.add(req);
            Function<RecordedRequest, MockResponse> h = stubs.get(req.method() + " " + req.path());
            MockResponse resp = h == null ? MockResponse.text(404, "no stub") : h.apply(req);
            if (!resp.delay().isZero()) {
                try {
                    Thread.sleep(resp.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            resp.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(resp.status(), resp.body().length == 0 ? -1 : resp.body().length);
            if (resp.body().length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(resp.body());
                }
            }
        } catch (IOException e) {
            // 客户端提前断开（超时、响应过大的用例）时写响应失败，忽略
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
