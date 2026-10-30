package com.maccms.backend.integration.addonmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 插件市场下载（B29，35 文档第 3 节）：请求地址与查询串（{@code http_build_query}）、请求头；JSON 下载地址的二次下载；市场拒绝；
 * 404/超时/超过包大小上限/连接失败/{@code loopback-only} 下的外部地址/未配置；市场令牌不进日志；地址配置的校验。只访问本机模拟服务。
 */
@ExtendWith(OutputCaptureExtension.class)
class FastadminAddonMarketTest {

    private static final String TOKEN = "test-only-market-token-b29";
    private static final byte[] ZIP = "PK\3\4fake-zip".getBytes(StandardCharsets.ISO_8859_1);

    private LoopbackMockServer mock;
    private final Deque<MockResponse> queue = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
        mock.stub("GET", "/api/addon/download", r -> {
            MockResponse m = queue.poll();
            return m == null ? MockResponse.text(599, "unplanned") : m;
        });
        mock.stub("GET", "/pkg/demo.zip", new MockResponse(200, Map.of("Content-Type", "application/zip"), ZIP, Duration.ZERO));
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private FastadminAddonMarket market(String api, int maxBytes, Duration read, OutboundPolicy.Mode mode) {
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(mode), IntegrationPackages.REGISTERED.keySet(),
                Duration.ofSeconds(2), read, 64, List.of());
        return new FastadminAddonMarket(client, AddonMarketConfiguration.checkApiUrl(api), maxBytes);
    }

    private FastadminAddonMarket market() {
        return market(mock.uri("/api/").toString(), 1 << 20, Duration.ofSeconds(5), OutboundPolicy.Mode.LOOPBACK_ONLY);
    }

    private static Map<String, String> extend(String uid, String token) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("uid", uid);
        m.put("token", token);
        m.put("version", null);
        m.put("faversion", null);
        return m;
    }

    @Test
    void downloadsThePackageWithTheLegacyQuery(CapturedOutput out) {
        queue.add(new MockResponse(200, Map.of("Content-Type", "application/zip"), ZIP, Duration.ZERO));
        assertThat(market().download("demo", extend("7", TOKEN + " ~*"))).isEqualTo(ZIP);
        RecordedRequest r = mock.requests().get(0);
        assertThat(r.path()).isEqualTo("/api/addon/download");
        assertThat(r.query()).isEqualTo("name=demo&uid=7&token=" + TOKEN + "+%7E%2A");
        assertThat(r.header("X-REQUESTED-WITH")).isEqualTo("XMLHttpRequest");
        assertThat(out.getAll()).contains("purpose=addonmarket.download").doesNotContain(TOKEN);
    }

    @Test
    void jsonWithUrlDownloadsAgainWithoutTheAjaxHeader() {
        queue.add(MockResponse.json(200, "{\"code\":1,\"msg\":\"ok\",\"data\":{\"url\":\"" + mock.uri("/pkg/demo.zip") + "\"}}"));
        assertThat(market().download("demo", extend(null, null))).isEqualTo(ZIP);
        assertThat(mock.requests()).hasSize(2);
        assertThat(mock.requests().get(0).query()).isEqualTo("name=demo");
        assertThat(mock.requests().get(1).path()).isEqualTo("/pkg/demo.zip");
        assertThat(mock.requests().get(1).header("X-REQUESTED-WITH")).isNull();
    }

    @Test
    void jsonWithoutUrlOrFailedSecondDownloadIsARefusal() {
        String refused = "{\"code\":0,\"msg\":\"插件不存在\",\"data\":null}";
        queue.add(MockResponse.json(200, refused));
        assertThatThrownBy(() -> market().download("demo", Map.of())).isInstanceOfSatisfying(AddonMarket.MarketRefused.class,
                e -> assertThat(e.json()).isEqualTo(refused));
        String broken = "{\"code\":1,\"msg\":\"请下载\",\"data\":{\"url\":\"http://127.0.0.1:1/x.zip\"}}";
        queue.add(MockResponse.json(200, broken));
        assertThatThrownBy(() -> market().download("demo", Map.of())).isInstanceOfSatisfying(AddonMarket.MarketRefused.class,
                e -> assertThat(e.json()).isEqualTo(broken));
        queue.add(MockResponse.json(200, "{not json"));
        assertThatThrownBy(() -> market().download("demo", Map.of())).isInstanceOf(AddonMarket.MarketRefused.class);
    }

    @Test
    void statusIsIgnoredLikeLegacy() {
        // 旧 Http::sendRequest 只看 curl 是否出错：404 的正文照样当成插件包，由解包校验拒绝
        queue.add(MockResponse.text(404, "not found"));
        assertThat(market().download("demo", Map.of())).isEqualTo("not found".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void failuresAreDownloadFailed() {
        queue.add(new MockResponse(200, Map.of(), new byte[2048], Duration.ZERO));
        assertThatThrownBy(() -> market(mock.uri("/api").toString(), 1024, Duration.ofSeconds(5), OutboundPolicy.Mode.LOOPBACK_ONLY)
                .download("demo", Map.of())).isInstanceOf(AddonMarket.DownloadFailed.class).hasMessage(AddonMarket.DOWNLOAD_FAILED);
        queue.add(MockResponse.text(200, "slow").withDelay(Duration.ofSeconds(3)));
        assertThatThrownBy(() -> market(mock.uri("/api").toString(), 1 << 20, Duration.ofMillis(300), OutboundPolicy.Mode.LOOPBACK_ONLY)
                .download("demo", Map.of())).isInstanceOf(AddonMarket.DownloadFailed.class);
        assertThatThrownBy(() -> market("http://127.0.0.1:1", 1 << 20, Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY)
                .download("demo", Map.of())).isInstanceOf(AddonMarket.DownloadFailed.class);
        assertThatThrownBy(() -> market("https://api.example.invalid", 1 << 20, Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY)
                .download("demo", Map.of())).isInstanceOf(AddonMarket.DownloadFailed.class);
        int before = mock.requests().size();
        assertThatThrownBy(() -> market("", 1 << 20, Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY).download("demo", Map.of()))
                .isInstanceOf(AddonMarket.DownloadFailed.class);
        assertThat(mock.requests()).hasSize(before);
    }

    @Test
    void apiUrlAndSizeConfiguration() {
        assertThat(AddonMarketConfiguration.checkApiUrl(null)).isEmpty();
        assertThat(AddonMarketConfiguration.checkApiUrl("  ")).isEmpty();
        assertThat(AddonMarketConfiguration.checkApiUrl("https://api.example.test/")).isEqualTo("https://api.example.test");
        for (String bad : List.of("ftp://x", "https://u:p@x", "https://x/?token=1", "https://x/#f", "not a url", "file:///etc")) {
            assertThatThrownBy(() -> AddonMarketConfiguration.checkApiUrl(bad)).as(bad).isInstanceOf(IllegalStateException.class);
        }
        assertThat(AddonMarketConfiguration.checkMaxBytes(16 << 20)).isEqualTo(16 << 20);
        assertThatThrownBy(() -> AddonMarketConfiguration.checkMaxBytes(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AddonMarketConfiguration.checkMaxBytes(Integer.MAX_VALUE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void downloadUrlFollowsPhpTruthiness() {
        assertThat(FastadminAddonMarket.downloadUrl("{\"data\":{\"url\":\"http://x/a.zip\"}}")).isEqualTo("http://x/a.zip");
        assertThat(FastadminAddonMarket.downloadUrl("{\"data\":{\"url\":null}}")).isNull();
        assertThat(FastadminAddonMarket.downloadUrl("{\"data\":\"http://x\"}")).isNull();
        assertThat(FastadminAddonMarket.downloadUrl("{\"data\":[]}")).isNull();
        assertThat(FastadminAddonMarket.downloadUrl("[1]")).isNull();
    }
}
