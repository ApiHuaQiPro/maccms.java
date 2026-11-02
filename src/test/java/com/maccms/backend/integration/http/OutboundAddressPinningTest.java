package com.maccms.backend.integration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.demo.DemoOutboundCaller;
import com.maccms.backend.integration.http.OutboundException.Reason;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * APPK-2 OUT-1-5（DEC-14 / NET-7，V-33 ①～⑥）：每一跳只解析一次，建连只用已检查的地址。
 *
 * <p>目标主机都用 {@code .invalid}（系统 DNS 必然解析失败，测试期护栏也会把任何系统解析登记为违规），由注入的解析器给出地址；
 * 禁止判定经包内构造器换成"只禁止 {@code 127.0.0.2}"，其余 open 模式规则照旧。所以引擎只要在建连时再按主机名解析一次，
 * 请求就会失败或被护栏判违规。请求经 {@link DemoOutboundCaller}（已登记的 {@code integration.demo}）发出。
 */
class OutboundAddressPinningTest {

    private static final String STORE_PASSWORD = "test-only-not-a-secret";
    private static final InetAddress LOOPBACK = address(127, 0, 0, 1);
    private static final InetAddress REBOUND = address(127, 0, 0, 2);

    private final Map<String, AtomicInteger> lookups = new ConcurrentHashMap<>();
    private final Map<String, List<InetAddress>> answers = new ConcurrentHashMap<>();
    private LoopbackMockServer mock;

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("不做系统 DNS 查询、只连回环地址").isEmpty();
    }

    private static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** 注入的解析器：按登记的应答依次返回（最后一个应答之后一直返回最后一个），并记录每个名字被解析的次数。 */
    private InetAddress[] resolve(String host) throws UnknownHostException {
        lookups.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
        List<InetAddress> a = answers.get(host);
        if (a == null) {
            throw new UnknownHostException("test");
        }
        int n = lookups.get(host).get();
        return new InetAddress[] {a.get(Math.min(n, a.size()) - 1)};
    }

    private int lookups(String host) {
        AtomicInteger n = lookups.get(host);
        return n == null ? 0 : n.get();
    }

    private OutboundHttpClient client(SSLContext ssl) throws Exception {
        OutboundPolicy policy = new OutboundPolicy(OutboundPolicy.Mode.OPEN, this::resolve, a -> a.equals(REBOUND));
        return new OutboundHttpClient(policy, Set.of("integration.demo"), Duration.ofSeconds(2), Duration.ofSeconds(5),
                1 << 16, List.of(), ssl == null ? SSLContext.getDefault() : ssl);
    }

    private static OutboundResponse send(OutboundHttpClient client, String url) {
        return DemoOutboundCaller.send(client, OutboundRequest.get("test.pinning", URI.create(url)));
    }

    /** ①：第一次解析给 127.0.0.1、之后给 127.0.0.2；请求到达 127.0.0.1，Host 是原主机名，每跳只解析一次。 */
    @Test
    void connectsToTheCheckedAddressWithoutResolvingAgain() throws Exception {
        answers.put("rebind.invalid", List.of(LOOPBACK, REBOUND));
        mock.stub("GET", "/a", MockResponse.text(200, "pinned"));
        OutboundHttpClient client = client(null);

        OutboundResponse r = send(client, "http://rebind.invalid:" + mock.port() + "/a");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo("pinned");
        assertThat(r.host()).isEqualTo("rebind.invalid:" + mock.port());
        assertThat(mock.requests()).singleElement()
                .satisfies(q -> assertThat(q.header("host")).isEqualTo("rebind.invalid:" + mock.port()));
        assertThat(lookups("rebind.invalid")).as("每跳只解析一次").isEqualTo(1);

        // 下一次请求重新检查：这次解析到被禁止的 127.0.0.2，检查阶段就拒绝，不发连接
        assertThatThrownBy(() -> send(client, "http://rebind.invalid:" + mock.port() + "/a"))
                .isInstanceOfSatisfying(OutboundException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TARGET_REJECTED));
        assertThat(lookups("rebind.invalid")).isEqualTo(2);
        assertThat(mock.requests()).hasSize(1);
    }

    /** ②：跳转后的一跳同样只解析一次、用固定地址；跳转目标解析到被禁止地址时拒绝，且那个地址上没有连接。 */
    @Test
    void eachRedirectHopIsPinnedAndRejectedHopsAreNeverConnected() throws Exception {
        answers.put("hop1.invalid", List.of(LOOPBACK));
        answers.put("hop2.invalid", List.of(LOOPBACK));
        mock.stub("GET", "/start", MockResponse.redirect(302, "http://hop2.invalid:" + mock.port() + "/landing"));
        mock.stub("GET", "/landing", MockResponse.text(200, "landed"));
        OutboundHttpClient client = client(null);

        OutboundResponse r = send(client, "http://hop1.invalid:" + mock.port() + "/start");
        assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo("landed");
        assertThat(r.host()).isEqualTo("hop2.invalid:" + mock.port());
        assertThat(mock.requests()).extracting(q -> q.header("host"))
                .containsExactly("hop1.invalid:" + mock.port(), "hop2.invalid:" + mock.port());
        assertThat(lookups("hop1.invalid")).isEqualTo(1);
        assertThat(lookups("hop2.invalid")).isEqualTo(1);

        try (CountingListener rebound = new CountingListener(REBOUND)) {
            answers.put("evil.invalid", List.of(REBOUND));
            mock.stub("GET", "/evil", MockResponse.redirect(302, "http://evil.invalid:" + rebound.port() + "/"));
            assertThatThrownBy(() -> send(client, "http://hop1.invalid:" + mock.port() + "/evil"))
                    .isInstanceOfSatisfying(OutboundException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.TARGET_REJECTED);
                        assertThat(e.host()).isEqualTo("evil.invalid:" + rebound.port());
                    });
            assertThat(lookups("evil.invalid")).isEqualTo(1);
            Thread.sleep(100);
            assertThat(rebound.accepted()).as("被拒绝的跳转目标没有收到连接").isZero();
        }
    }

    /** 连接不跨跳、不跨请求复用：服务端保持连接，客户端每跳仍新建连接。 */
    @Test
    void connectionsAreNeverReusedAcrossHopsOrRequests() throws Exception {
        answers.put("keep.invalid", List.of(LOOPBACK));
        try (KeepAliveServer server = new KeepAliveServer()) {
            OutboundHttpClient client = client(null);
            String base = "http://keep.invalid:" + server.port();
            assertThat(send(client, base + "/redirect").bodyText(StandardCharsets.UTF_8)).isEqualTo("ok");
            assertThat(send(client, base + "/plain").bodyText(StandardCharsets.UTF_8)).isEqualTo("ok");
            assertThat(server.paths).containsExactly("/redirect", "/end", "/plain");
            assertThat(server.accepted.get()).as("三跳三条连接").isEqualTo(3);
            assertThat(lookups("keep.invalid")).as("每跳各解析一次").isEqualTo(3);
        }
    }

    /** ③：SNI 是原主机名；证书主机名匹配时成功，不匹配时 IO_ERROR。 */
    @Test
    void tlsUsesTheOriginalHostNameForSniAndCertificateCheck() throws Exception {
        KeyStore pin = keyStore("pin.invalid");
        KeyStore other = keyStore("other.invalid");
        SSLContext trustBoth = trusting(pin, other);
        answers.put("pin.invalid", List.of(LOOPBACK));
        OutboundHttpClient client = client(trustBoth);

        try (TlsServer server = new TlsServer(pin)) {
            OutboundResponse r = send(client, "https://pin.invalid:" + server.port() + "/");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo("ok");
            assertThat(server.sni).containsExactly("pin.invalid");
            assertThat(server.hosts).containsExactly("pin.invalid:" + server.port());
        }
        try (TlsServer server = new TlsServer(other)) {
            assertThatThrownBy(() -> send(client, "https://pin.invalid:" + server.port() + "/"))
                    .isInstanceOfSatisfying(OutboundException.class, e -> assertThat(e.reason()).isEqualTo(Reason.IO_ERROR));
            assertThat(server.sni).as("握手时仍按原主机名发 SNI").containsExactly("pin.invalid");
            assertThat(server.hosts).as("证书不符时不发请求").isEmpty();
        }
        assertThat(lookups("pin.invalid")).isEqualTo(2);
    }

    /** ④：设了系统代理（ProxySelector 与 http.proxyHost）也直连：选择器没被询问，代理监听没有连接。 */
    @Test
    void systemProxySettingsAreIgnored() throws Exception {
        answers.put("direct.invalid", List.of(LOOPBACK));
        mock.stub("GET", "/p", MockResponse.text(200, "direct"));
        OutboundHttpClient client = client(null);
        ProxySelector previous = ProxySelector.getDefault();
        String httpHost = System.getProperty("http.proxyHost");
        String httpPort = System.getProperty("http.proxyPort");
        AtomicInteger asked = new AtomicInteger();
        try (CountingListener proxy = new CountingListener(LOOPBACK)) {
            InetSocketAddress at = new InetSocketAddress(LOOPBACK, proxy.port());
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    asked.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.SOCKS, at));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            });
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(proxy.port()));

            OutboundResponse r = send(client, "http://direct.invalid:" + mock.port() + "/p");
            assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo("direct");
            assertThat(mock.requests()).singleElement()
                    .satisfies(q -> assertThat(q.header("host")).isEqualTo("direct.invalid:" + mock.port()));
            Thread.sleep(100);
            assertThat(asked.get()).as("系统 ProxySelector 未被询问").isZero();
            assertThat(proxy.accepted()).as("代理监听没有连接").isZero();
        } finally {
            ProxySelector.setDefault(previous);
            restore("http.proxyHost", httpHost);
            restore("http.proxyPort", httpPort);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** ⑤：调用方给出受限头 → IllegalArgumentException（同 JDK 客户端），不发出请求。 */
    @Test
    void restrictedHeadersAreRejectedBeforeConnecting() throws Exception {
        answers.put("headers.invalid", List.of(LOOPBACK));
        OutboundHttpClient client = client(null);
        URI uri = URI.create("http://headers.invalid:" + mock.port() + "/h");
        for (String name : List.of("Host", "connection", "Content-Length", "EXPECT", "Upgrade")) {
            assertThatThrownBy(() -> DemoOutboundCaller.send(client,
                    OutboundRequest.get("test.pinning", uri).withHeader(name, "x"))).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mock.requests()).isEmpty();
    }

    /** ⑥：不发 Accept-Encoding，不存也不回送 cookie；POST 无请求体时 Content-Length: 0；用户代理沿用旧版。 */
    @Test
    void requestHeadersCarryNoCompressionOrCookies() throws Exception {
        answers.put("headers.invalid", List.of(LOOPBACK));
        mock.stub("GET", "/set", MockResponse.redirect(302, "/after").withHeader("Set-Cookie", "sid=test-only; Path=/"));
        mock.stub("GET", "/after", MockResponse.text(200, "after"));
        mock.stub("POST", "/empty", MockResponse.text(200, "empty"));
        OutboundHttpClient client = client(null);

        assertThat(send(client, "http://headers.invalid:" + mock.port() + "/set").bodyText(StandardCharsets.UTF_8))
                .isEqualTo("after");
        DemoOutboundCaller.send(client, new OutboundRequest("test.pinning", "POST",
                URI.create("http://headers.invalid:" + mock.port() + "/empty"), Map.of(), null));
        List<RecordedRequest> reqs = mock.requests();
        assertThat(reqs).hasSize(3);
        assertThat(reqs).allSatisfy(q -> {
            assertThat(q.header("accept-encoding")).isNull();
            assertThat(q.header("cookie")).isNull();
            assertThat(q.header("connection")).as("同基线 JDK 客户端，不发 Connection 头").isNull();
            assertThat(q.header("user-agent")).isEqualTo(OutboundHttpClient.LEGACY_USER_AGENT);
        });
        assertThat(reqs.get(2).header("content-length")).isEqualTo("0");
    }

    // ---- 本机测试服务 ----

    private static KeyStore keyStore(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = OutboundAddressPinningTest.class.getResourceAsStream("/outbound-tls/" + name + ".p12")) {
            assertThat(in).as(name).isNotNull();
            ks.load(in, STORE_PASSWORD.toCharArray());
        }
        return ks;
    }

    /** 只信任给定的两张自签证书（主机名校验仍由客户端按原主机名做）。 */
    private static SSLContext trusting(KeyStore... stores) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int i = 0;
        for (KeyStore ks : stores) {
            String alias = ks.aliases().nextElement();
            trust.setCertificateEntry("c" + i++, ks.getCertificate(alias));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** 读到空行为止的请求头（小写的 Host 值、请求行）。 */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        for (int b; (b = in.read()) != -1; ) {
            buf.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) {
                return buf.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static String hostHeader(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                return line.substring(5).strip();
            }
        }
        return null;
    }

    /** 只计数收到的连接（被拒绝的跳转目标、"代理"）。 */
    private static final class CountingListener implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger accepted = new AtomicInteger();

        CountingListener(InetAddress bind) throws IOException {
            socket = new ServerSocket(0, 50, bind);
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        accepted.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "counting-listener");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 保持连接的 HTTP/1.1 服务：{@code /redirect} 302 到 {@code /end}，其余 200；记录路径与连接数。 */
    private static final class KeepAliveServer implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger accepted = new AtomicInteger();
        private final List<String> paths = new CopyOnWriteArrayList<>();

        KeepAliveServer() throws IOException {
            socket = new ServerSocket(0, 50, LOOPBACK);
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket s = socket.accept();
                        accepted.incrementAndGet();
                        Thread c = new Thread(() -> serve(s), "keep-alive-conn");
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "keep-alive-server");
            t.setDaemon(true);
            t.start();
        }

        private void serve(Socket s) {
            try (s) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                for (String head; (head = readHead(in)) != null; ) {
                    String path = head.split(" ", 3)[1];
                    paths.add(path);
                    String resp = path.equals("/redirect")
                            ? "HTTP/1.1 302 Found\r\nLocation: /end\r\nContent-Length: 0\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok";
                    out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
            } catch (IOException e) {
                // 客户端关闭连接
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** HTTPS 服务：记录客户端请求的 SNI 与 Host，回 200 "ok"。 */
    private static final class TlsServer implements AutoCloseable {

        private final SSLServerSocket socket;
        private final List<String> sni = new CopyOnWriteArrayList<>();
        private final List<String> hosts = new CopyOnWriteArrayList<>();

        TlsServer(KeyStore identity) throws Exception {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(identity, STORE_PASSWORD.toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            socket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 50, LOOPBACK);
            // 在 ClientHello 阶段记下客户端请求的服务器名（握手因证书不符失败时也记得到）
            SSLParameters params = socket.getSSLParameters();
            params.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                @Override
                public boolean matches(SNIServerName name) {
                    sni.add(((SNIHostName) name).getAsciiName());
                    return true;
                }
            }));
            socket.setSSLParameters(params);
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (SSLSocket s = (SSLSocket) socket.accept()) {
                        s.setSoTimeout(5000);
                        try {
                            s.startHandshake();
                        } catch (IOException handshakeFailed) {
                            continue;
                        }
                        String head = readHead(s.getInputStream());
                        if (head == null) {
                            continue;
                        }
                        hosts.add(hostHeader(head));
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n"
                                + "Connection: close\r\n\r\nok").getBytes(StandardCharsets.ISO_8859_1));
                        s.getOutputStream().flush();
                    } catch (IOException e) {
                        if (socket.isClosed()) {
                            return;
                        }
                    }
                }
            }, "tls-server");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
