package com.maccms.backend.integration.urlsend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 百度推送（B28，30 文档第 3 节）：请求地址、参数与请求体；三种结果与快速收录的重推；超时、响应过大、跳转到外部主机、
 * {@code loopback-only} 模式下的外部地址一律按"请求失败"（101）处理而不抛出；令牌不进日志。只访问本机模拟服务。
 */
@ExtendWith(OutputCaptureExtension.class)
class BaiduUrlPushTest {

    private static final String BAIDU_TOKEN = "test-only-baidu-token-b28";
    private static final String FAST_TOKEN = "test-only-fast-token-b28";
    private static final String SITE = "http://www.example.test";
    private static final List<String> URLS = List.of(SITE + "/index.php/vod/detail/id/1.html", SITE + "/index.php/vod/detail/id/2.html",
            SITE + "/index.php/vod/detail/id/3.html");

    private LoopbackMockServer mock;
    private final Deque<MockResponse> queue = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
        mock.stub("POST", "/urls", r -> {
            MockResponse m = queue.poll();
            return m == null ? MockResponse.json(599, "unplanned") : m;
        });
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private BaiduUrlPush push(Duration read, int max) {
        return push(read, max, OutboundPolicy.Mode.LOOPBACK_ONLY, mock.uri("/urls").toString());
    }

    private BaiduUrlPush push(Duration read, int max, OutboundPolicy.Mode mode, String endpoint) {
        ThirdPartyConfig config = ThirdPartyConfig.from(new PhpArray(Map.of("urlsend", new PhpArray(Map.of(
                "baidu", new PhpArray(Map.of("token", new PhpValue.PhpString(BAIDU_TOKEN))),
                "baidufast", new PhpArray(Map.of("token", new PhpValue.PhpString(FAST_TOKEN))))))));
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(mode), IntegrationPackages.REGISTERED.keySet(),
                Duration.ofSeconds(2), read, max, config.secretValues());
        return new BaiduUrlPush(client, config, UrlsendConfiguration.checkEndpoint(endpoint));
    }

    private BaiduUrlPush push() {
        return push(Duration.ofSeconds(5), 1 << 16);
    }

    private void reply(String json) {
        queue.add(MockResponse.json(200, json));
    }

    @Test
    void normalPushSendsTheLegacyRequest(CapturedOutput output) {
        reply("{\"remain\":498,\"success\":3}");
        UrlPush.Result r = push().submit("baidu", SITE, URLS);
        assertThat(r).isEqualTo(new UrlPush.Result(1, "推送成功3条；当天剩余可推498条。"));
        RecordedRequest q = mock.requests().get(0);
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.path()).isEqualTo("/urls");
        assertThat(q.query()).isEqualTo("site=" + SITE + "&token=" + BAIDU_TOKEN);
        assertThat(q.header("content-type")).isEqualTo("text/plain");
        assertThat(q.bodyText()).isEqualTo(String.join("\n", URLS));
        assertThat(q.header("user-agent")).startsWith("Mozilla/5.0 (Windows NT 10.0; WOW64)");
        // 令牌只在地址里，不进日志（客户端只记主机）
        assertThat(output.getAll()).contains("outbound purpose=urlsend.baidu").doesNotContain(BAIDU_TOKEN).doesNotContain("/urls");
    }

    @Test
    void errorAndInvalidResponses() {
        reply("{\"error\":401,\"message\":\"token is not valid\"}");
        assertThat(push().submit("baidu", SITE, URLS)).isEqualTo(new UrlPush.Result(102, "发生错误：token is not valid"));
        queue.add(MockResponse.json(400, "{\"error\":400,\"message\":\"over quota\"}"));
        assertThat(push().submit("baidu", SITE, URLS)).isEqualTo(new UrlPush.Result(102, "发生错误：over quota"));
        for (String bad : List.of("not json", "", "0", "[]", "{}", "\"0\"", "false", "null")) {
            queue.add(MockResponse.json(200, bad));
            assertThat(push().submit("baidu", SITE, URLS)).as(bad).isEqualTo(new UrlPush.Result(101, "请求失败，请重试"));
        }
        // 只有 error 为真才是 102（0、空串不算）
        reply("{\"error\":0,\"success\":1,\"remain\":2}");
        assertThat(push().submit("baidu", SITE, URLS).code()).isEqualTo(1);
        reply("{\"error\":\"x\",\"message\":[1]}");
        assertThat(push().submit("baidu", SITE, URLS)).isEqualTo(new UrlPush.Result(102, "发生错误：Array"));
    }

    @Test
    void fastPushResendsTheFirstSuccessUrlsWhenQuotaIsUsedUp() {
        reply("{\"remain\":5,\"success\":3}");
        // 旧版提示把 remain 与 success 写反了
        assertThat(push().submit("baidufast", SITE, URLS)).isEqualTo(new UrlPush.Result(1, "推送5条；剩余可推送3条."));
        assertThat(mock.requests().get(0).query()).isEqualTo("site=" + SITE + "&token=" + FAST_TOKEN + "&type=daily");

        reply("{\"remain\":0,\"success\":2}");
        reply("{\"remain\":0,\"success\":2}");
        assertThat(push().submit("baidufast", SITE, URLS)).isEqualTo(new UrlPush.Result(1, "推送0条；剩余可推送2条."));
        List<RecordedRequest> reqs = mock.requests();
        assertThat(reqs).hasSize(3);
        assertThat(reqs.get(2).bodyText()).isEqualTo(URLS.get(0) + "\n" + URLS.get(1));

        reply("{\"remain\":\"0\",\"success\":\"9\"}");
        queue.add(MockResponse.json(200, "x"));
        assertThat(push().submit("baidufast", SITE, URLS)).isEqualTo(new UrlPush.Result(103, "请求失败，请重试2"));
        assertThat(mock.requests().get(4).bodyText()).isEqualTo(String.join("\n", URLS));

        reply("{\"remain\":null,\"success\":1}");
        reply("{\"error\":1,\"message\":\"daily quota\"}");
        assertThat(push().submit("baidufast", SITE, URLS)).isEqualTo(new UrlPush.Result(104, "发生错误2：daily quota"));

        // success 不大于 0 时不重推
        reply("{\"remain\":0,\"success\":0}");
        assertThat(push().submit("baidufast", SITE, URLS)).isEqualTo(new UrlPush.Result(1, "推送0条；剩余可推送0条."));
        assertThat(mock.requests()).hasSize(8);
    }

    @Test
    void timeoutOversizeAndRedirectsAreRequestFailures() {
        queue.add(MockResponse.json(200, "{\"success\":1,\"remain\":1}").withDelay(Duration.ofSeconds(3)));
        assertThat(push(Duration.ofMillis(500), 1 << 16).submit("baidu", SITE, URLS).code()).isEqualTo(101);

        queue.add(MockResponse.json(200, "{\"success\":1,\"remain\":1,\"pad\":\"" + "x".repeat(4096) + "\"}"));
        assertThat(push(Duration.ofSeconds(5), 1024).submit("baidu", SITE, URLS).code()).isEqualTo(101);

        // 跳到外部主机：测试配置只允许回环地址，跳转目标被拒绝，不建立连接
        queue.add(MockResponse.redirect(302, "http://203.0.113.7/urls"));
        assertThat(push().submit("baidu", SITE, URLS).code()).isEqualTo(101);
    }

    @Test
    void outboundDisabledModeFailsWithoutConnecting() {
        // 紧急停止出站（MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only）时，默认的百度地址被拒绝：请求失败，不抛出、不连接
        BaiduUrlPush p = push(Duration.ofSeconds(5), 1 << 16, OutboundPolicy.Mode.LOOPBACK_ONLY, UrlsendConfiguration.DEFAULT_ENDPOINT);
        assertThat(p.submit("baidu", SITE, URLS)).isEqualTo(new UrlPush.Result(101, "请求失败，请重试"));
        assertThat(p.submit("baidufast", SITE, URLS).code()).isEqualTo(101);
        assertThat(mock.requests()).isEmpty();
    }

    @Test
    void tokensThatDoNotFormAValidUrlAreRequestFailures() {
        ThirdPartyConfig config = ThirdPartyConfig.from(new PhpArray(Map.of("urlsend", new PhpArray(Map.of(
                "baidu", new PhpArray(Map.of("token", new PhpValue.PhpString("has space970"))))))));
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(2), 1024, List.of());
        BaiduUrlPush p = new BaiduUrlPush(client, config, mock.uri("/urls").toString());
        assertThat(p.submit("baidu", SITE, URLS).code()).isEqualTo(101);
        assertThat(mock.requests()).isEmpty();
        assertThatThrownBy(() -> p.submit("google", SITE, URLS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointMustBeAPlainHttpUrl() {
        assertThat(UrlsendConfiguration.checkEndpoint(null)).isEqualTo("http://data.zz.baidu.com/urls");
        assertThat(UrlsendConfiguration.checkEndpoint(" ")).isEqualTo("http://data.zz.baidu.com/urls");
        assertThat(UrlsendConfiguration.checkEndpoint("https://data.zz.baidu.com/urls")).isEqualTo("https://data.zz.baidu.com/urls");
        for (String bad : List.of("ftp://data.zz.baidu.com/urls", "http://data.zz.baidu.com/urls?x=1", "http://u:p@host/urls",
                "/urls", "http://host/urls#f", "http://a b/")) {
            assertThatThrownBy(() -> UrlsendConfiguration.checkEndpoint(bad)).as(bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("u:p");
        }
    }

    @Test
    void phpJsonHelpers() {
        assertThat(PhpJsonResult.leadingNumber("12abc")).isEqualByComparingTo("12");
        assertThat(PhpJsonResult.leadingNumber(" -3.5x")).isEqualByComparingTo("-3.5");
        assertThat(PhpJsonResult.leadingNumber("abc")).isEqualByComparingTo("0");
        assertThat(PhpJsonResult.leadingNumber(".5")).isEqualByComparingTo("0.5");
        assertThat(PhpJsonResult.text(PhpJsonResult.decode("{\"a\":1.50}".getBytes()).get("a"))).isEqualTo("1.5");
        assertThat(PhpJsonResult.text(PhpJsonResult.decode("{\"a\":2.0}".getBytes()).get("a"))).isEqualTo("2");
        assertThat(PhpJsonResult.text(PhpJsonResult.decode("{\"a\":true}".getBytes()).get("a"))).isEqualTo("1");
    }
}
