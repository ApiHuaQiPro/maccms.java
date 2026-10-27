package com.maccms.backend.integration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.LogRedactor;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundException.Reason;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 出站客户端（B23 ②⑤）：本测试类所在的 {@code integration.demo} 只在测试里存在，作为"已登记子包"的样例，
 * 演示 B24～B29 如何用 {@link LoopbackMockServer} 测试自己的第三方调用。所有请求只发往回环地址上的模拟服务。
 * 假密钥一律是一看即知的占位值。
 */
@ExtendWith(OutputCaptureExtension.class)
class RegisteredOutboundClientTest {

    private static final String FAKE_KEY = "test-only-not-a-secret-appkey";
    private static final String FAKE_TOKEN = "test-only-access-token-0001";
    private static final String FAKE_SIGN = "test-only-sign-ABCDEF";
    private static final String FAKE_CODE = "735921";
    private static final String FAKE_MOBILE = "13800138000";

    private LoopbackMockServer mock;
    private OutboundHttpClient client;

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
        client = client(Duration.ofSeconds(5), 1 << 16);
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private static OutboundHttpClient client(Duration read, int max) {
        return new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY), Set.of("integration.demo"),
                Duration.ofSeconds(2), read, max, List.of(FAKE_KEY));
    }

    @Test
    void getAndPostThroughTheClient() {
        mock.stub("GET", "/api/status", MockResponse.json(200, "{\"ok\":true}"));
        mock.stub("POST", "/api/order", r -> MockResponse.json(200, "{\"echo\":" + r.body().length + "}"));

        OutboundResponse g = client.send(OutboundRequest.get("demo.status", mock.uri("/api/status?x=1")));
        assertThat(g.status()).isEqualTo(200);
        assertThat(g.bodyText(StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(g.header("Content-Type")).hasValue("application/json; charset=utf-8");
        assertThat(g.host()).isEqualTo("127.0.0.1:" + mock.port());

        byte[] form = "a=1&b=2".getBytes(StandardCharsets.UTF_8);
        OutboundResponse p = client.send(OutboundRequest.post("demo.order", mock.uri("/api/order"),
                "application/x-www-form-urlencoded", form).withHeader("X-Demo", "1"));
        assertThat(p.bodyText(StandardCharsets.UTF_8)).isEqualTo("{\"echo\":7}");

        List<RecordedRequest> reqs = mock.requests();
        assertThat(reqs).hasSize(2);
        assertThat(reqs.get(0).method()).isEqualTo("GET");
        assertThat(reqs.get(0).query()).isEqualTo("x=1");
        assertThat(reqs.get(0).header("user-agent")).startsWith("Mozilla/5.0 (Windows NT 10.0; WOW64)");
        assertThat(reqs.get(1).bodyText()).isEqualTo("a=1&b=2");
        assertThat(reqs.get(1).header("content-type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(reqs.get(1).header("x-demo")).isEqualTo("1");
    }

    @Test
    void nonSuccessStatusIsReturnedNotThrown() {
        mock.stub("GET", "/err", MockResponse.text(500, "boom"));
        assertThat(client.send(OutboundRequest.get("demo.err", mock.uri("/err"))).status()).isEqualTo(500);
    }

    @Test
    void redirectsAreFollowedLikeCurlAndEachHopIsChecked() {
        mock.stub("POST", "/r1", MockResponse.redirect(302, "/r2"));
        mock.stub("GET", "/r2", MockResponse.text(200, "done"));
        OutboundResponse r = client.send(OutboundRequest.post("demo.redirect", mock.uri("/r1"), "text/plain",
                "x".getBytes(StandardCharsets.UTF_8)));
        assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo("done");
        // 302 后 POST 改为不带请求体的 GET（同 curl）
        assertThat(mock.requests()).extracting(RecordedRequest::method).containsExactly("POST", "GET");
        assertThat(mock.requests().get(1).body()).isEmpty();

        // 跳到外部主机：在测试配置下目标检查直接拒绝，不发出连接
        mock.stub("GET", "/out", MockResponse.redirect(302, "http://203.0.113.9/steal"));
        assertThatThrownBy(() -> client.send(OutboundRequest.get("demo.redirect", mock.uri("/out"))))
                .isInstanceOfSatisfying(OutboundException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TARGET_REJECTED);
                    assertThat(e.host()).isEqualTo("203.0.113.9");
                });

        // 跳转次数上限
        mock.stub("GET", "/loop", MockResponse.redirect(307, "/loop"));
        assertThatThrownBy(() -> client.send(OutboundRequest.get("demo.redirect", mock.uri("/loop"))))
                .isInstanceOfSatisfying(OutboundException.class, e -> assertThat(e.reason()).isEqualTo(Reason.REDIRECT_FAILED));
        assertThat(mock.requests().stream().filter(q -> q.path().equals("/loop")).count()).isEqualTo(6);
    }

    @Test
    void crossOriginRedirectDropsCredentialHeaders() {
        try (LoopbackMockServer other = LoopbackMockServer.start()) {
            other.stub("GET", "/landing", MockResponse.text(200, "ok"));
            mock.stub("GET", "/go", MockResponse.redirect(302, "http://localhost:" + other.port() + "/landing"));
            client.send(OutboundRequest.get("demo.redirect", mock.uri("/go"))
                    .withHeader("Authorization", "Bearer " + FAKE_TOKEN).withHeader("X-Keep", "1"));
            assertThat(mock.requests().get(0).header("authorization")).isEqualTo("Bearer " + FAKE_TOKEN);
            RecordedRequest landed = other.requests().get(0);
            assertThat(landed.header("authorization")).isNull();
            assertThat(landed.header("x-keep")).isEqualTo("1");
        }
    }

    @Test
    void timeoutAndOversizedResponsesFail(CapturedOutput output) {
        mock.stub("GET", "/slow", MockResponse.text(200, "late").withDelay(Duration.ofSeconds(3)));
        OutboundHttpClient fast = client(Duration.ofMillis(300), 1 << 16);
        assertThatThrownBy(() -> fast.send(OutboundRequest.get("demo.slow",
                mock.uri("/slow?access_token=" + FAKE_TOKEN))))
                .isInstanceOfSatisfying(OutboundException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
                    assertThat(e.getMessage()).isEqualTo("TIMEOUT host=127.0.0.1:" + mock.port());
                });

        mock.stub("GET", "/big", MockResponse.text(200, "x".repeat(5000)));
        OutboundHttpClient small = client(Duration.ofSeconds(5), 1000);
        assertThatThrownBy(() -> small.send(OutboundRequest.get("demo.big", mock.uri("/big"))))
                .isInstanceOfSatisfying(OutboundException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.RESPONSE_TOO_LARGE));

        assertThat(output.getAll()).contains("outbound failed reason=timeout host=127.0.0.1:" + mock.port())
                .doesNotContain(FAKE_TOKEN).doesNotContain("/slow");
    }

    @Test
    void connectionRefusedIsReported() throws Exception {
        int closedPort;
        try (LoopbackMockServer gone = LoopbackMockServer.start()) {
            closedPort = gone.port();
        }
        assertThatThrownBy(() -> client.send(OutboundRequest.get("demo.refused",
                URI.create("http://127.0.0.1:" + closedPort + "/"))))
                .isInstanceOfSatisfying(OutboundException.class,
                        e -> assertThat(e.reason()).isIn(Reason.CONNECT_FAILED, Reason.IO_ERROR));
    }

    /**
     * 验收：含假密钥的请求、第三方响应经日志输出后不含密钥、签名、token、验证码、手机号；日志只记目标主机。
     */
    @Test
    void logsCarryNoSecretsSignaturesTokensCodesOrMobiles(CapturedOutput output) {
        String leaky = "{\"errcode\":40001,\"access_token\":\"" + FAKE_TOKEN + "\",\"sign\":\"" + FAKE_SIGN
                + "\",\"code\":\"" + FAKE_CODE + "\",\"mobile\":\"" + FAKE_MOBILE + "\",\"echo_key\":\"" + FAKE_KEY
                + "\",\"note\":\"user " + FAKE_MOBILE + " key=" + FAKE_KEY + "\"}";
        mock.stub("POST", "/oauth/token", MockResponse.json(400, leaky));
        mock.stub("GET", "/ok", MockResponse.text(200, "ok"));

        OutboundResponse r = client.send(OutboundRequest.post("demo.token",
                mock.uri("/oauth/token?appkey=" + FAKE_KEY + "&sign=" + FAKE_SIGN + "&mobile=" + FAKE_MOBILE),
                "application/x-www-form-urlencoded",
                ("code=" + FAKE_CODE + "&secret=" + FAKE_KEY).getBytes(StandardCharsets.UTF_8))
                .withHeader("Authorization", "Bearer " + FAKE_TOKEN));
        // 调用方拿到的响应是原文
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.bodyText(StandardCharsets.UTF_8)).isEqualTo(leaky);
        client.send(OutboundRequest.get("demo.ok", mock.uri("/ok?access_token=" + FAKE_TOKEN)));

        String logs = output.getAll();
        assertThat(logs).contains("outbound purpose=demo.token", "host=127.0.0.1:" + mock.port(), "status=400",
                "\"errcode\":40001", LogRedactor.MASK, "outbound purpose=demo.ok");
        for (String secret : List.of(FAKE_KEY, FAKE_TOKEN, FAKE_SIGN, FAKE_CODE, FAKE_MOBILE, "/oauth/token", "appkey=")) {
            assertThat(logs).as(secret).doesNotContain(secret);
        }
        // 请求对象、响应对象的 toString 同样不带内容
        assertThat(r.toString()).doesNotContain(FAKE_TOKEN).contains("400");
        assertThat(OutboundRequest.get("demo.x", mock.uri("/p?sign=" + FAKE_SIGN)).toString()).doesNotContain(FAKE_SIGN);
    }

    @Test
    void targetPolicyRejectsBeforeConnecting() {
        for (String bad : List.of("http://203.0.113.5/", "https://api.provider.invalid/", "ftp://127.0.0.1/",
                "http://user:" + FAKE_KEY + "@127.0.0.1:" + mock.port() + "/")) {
            assertThatThrownBy(() -> client.send(OutboundRequest.get("demo.bad", URI.create(bad)))).as(bad)
                    .isInstanceOfSatisfying(OutboundException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.TARGET_REJECTED);
                        assertThat(e.getMessage()).doesNotContain(FAKE_KEY);
                    });
        }
        assertThat(mock.requests()).isEmpty();
    }
}
