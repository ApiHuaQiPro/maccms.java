package com.maccms.backend.integration.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 测试期出站护栏（B23 ⑥）自检：合成的"连接外部主机"用例都被拦下并登记违规，回环模拟服务正常可用。
 * 外部目标用 TEST-NET-3（203.0.113.0/24，RFC 5737 文档地址，不可路由）与保留域名 {@code .invalid}；拦截发生在连接之前，
 * 不会真的发出任何包。每个用例最后取走违规，免得自动注册的扩展判本类失败。
 */
class TestOutboundGuardTest {

    private static final String EXTERNAL_IP = "203.0.113.7";

    @AfterEach
    void drain() {
        TestOutboundGuard.drainViolations();
    }

    @Test
    void guardIsInstalledAutomatically() {
        assertThat(TestOutboundGuard.isInstalled()).isTrue();
        assertThat(ServiceLoader.load(java.net.spi.InetAddressResolverProvider.class).stream()
                .map(p -> p.type().getName()).toList()).contains(LoopbackOnlyResolverProvider.class.getName());
    }

    @Test
    void jdkHttpClientToExternalIpIsBlocked() {
        HttpClient c = HttpClient.newHttpClient();
        assertThatThrownBy(() -> c.send(HttpRequest.newBuilder(URI.create("http://" + EXTERNAL_IP + "/x")).build(),
                HttpResponse.BodyHandlers.discarding())).isNotNull();
        assertThat(TestOutboundGuard.drainViolations()).containsExactly("连接非回环地址 http://" + EXTERNAL_IP);
    }

    @Test
    void plainSocketToExternalIpIsBlocked() {
        assertThatThrownBy(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(EXTERNAL_IP, 443), 200);
            }
        }).isInstanceOf(IOException.class);
        assertThat(TestOutboundGuard.drainViolations()).containsExactly("连接非回环地址 socket://" + EXTERNAL_IP + ":443");
    }

    @Test
    void httpUrlConnectionToExternalIpIsBlocked() {
        assertThatThrownBy(() -> {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://" + EXTERNAL_IP + ":8080/").toURL().openConnection();
            c.setConnectTimeout(200);
            c.connect();
        }).isInstanceOf(IOException.class);
        assertThat(TestOutboundGuard.drainViolations()).containsExactly("连接非回环地址 http://" + EXTERNAL_IP + ":8080");
    }

    @Test
    void externalHostNamesAreNotResolved() {
        assertThatThrownBy(() -> InetAddress.getByName("api.provider.invalid"))
                .isInstanceOf(UnknownHostException.class).hasMessageContaining("测试期出站护栏");
        assertThat(TestOutboundGuard.drainViolations()).containsExactly("解析外部主机名 api.provider.invalid");
    }

    @Test
    void extensionFailsTheTestWhenAViolationWasRecorded() {
        TestOutboundGuard.record("连接非回环地址 http://" + EXTERNAL_IP);
        assertThatThrownBy(OutboundGuardExtension::check).isInstanceOf(AssertionError.class)
                .hasMessageContaining(EXTERNAL_IP);
        // check 已清空，再查一次不失败
        OutboundGuardExtension.check();
    }

    @Test
    void loopbackTargetsAreAllowed() throws Exception {
        assertThat(InetAddress.getByName("localhost").isLoopbackAddress()).isTrue();
        try (LoopbackMockServer mock = LoopbackMockServer.start()) {
            mock.stub("GET", "/ping", MockResponse.text(200, "pong"));
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(mock.uri("/ping?a=1")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("pong");
            HttpResponse<String> viaName = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + mock.port() + "/nostub")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(viaName.statusCode()).isEqualTo(404);
            assertThat(mock.requests()).extracting(LoopbackMockServer.RecordedRequest::path).containsExactly("/ping", "/nostub");
            assertThat(mock.requests().get(0).query()).isEqualTo("a=1");
        }
        assertThat(TestOutboundGuard.drainViolations()).isEmpty();
    }

    @Test
    void loopbackHostLiterals() {
        for (String h : List.of("localhost", "LOCALHOST", "a.localhost", "127.0.0.1", "127.1.2.3", "[::1]", "::1")) {
            assertThat(TestOutboundGuard.isLoopbackHost(h)).as(h).isTrue();
        }
        for (String h : List.of("", "example.com", "localhost.example.com", "128.0.0.1", "10.0.0.1", "[::2]", "1270.0.0.1")) {
            assertThat(TestOutboundGuard.isLoopbackHost(h)).as(h).isFalse();
        }
        assertThat(TestOutboundGuard.isLoopbackHost(null)).isFalse();
        // Socket 连 IPv6 时 JDK 给出的 URI 解析不出主机（方括号被编码或没有方括号）
        assertThat(TestOutboundGuard.hostOf(URI.create("socket://%5b0:0:0:0:0:0:0:1%5d:8080"))).isEqualTo("[0:0:0:0:0:0:0:1]");
        assertThat(TestOutboundGuard.isLoopbackHost("[0:0:0:0:0:0:0:1]")).isTrue();
        assertThat(TestOutboundGuard.hostOf(URI.create("socket://::1:8080"))).isEqualTo("::1");
        assertThat(TestOutboundGuard.hostOf(URI.create("socket://2001:db8::1:443"))).isEqualTo("2001:db8::1");
        assertThat(TestOutboundGuard.hostOf(URI.create("socket://127.0.0.1:3306"))).isEqualTo("127.0.0.1");
        assertThat(TestOutboundGuard.hostOf(URI.create("http://[::1]:80/x"))).isEqualTo("[::1]");
    }

    @Test
    void ipv6LoopbackSocketIsAllowedAndExternalIpv6IsBlocked() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, InetAddress.getByName("::1"));
             Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName("::1"), server.getLocalPort()), 1000);
            assertThat(s.isConnected()).isTrue();
        }
        assertThat(TestOutboundGuard.drainViolations()).isEmpty();
        assertThatThrownBy(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName("2001:db8::7"), 443), 200);
            }
        }).isInstanceOf(IOException.class);
        assertThat(TestOutboundGuard.drainViolations()).hasSize(1);
    }
}
