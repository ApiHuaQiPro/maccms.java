package com.maccms.backend.integration.backlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 友链回链检测的页面抓取（B28，30 文档第 4 节）：任何状态码都返回正文、跟随跳转；超时、响应过大、跳转过多、连接失败、
 * 非 http/https、带凭证的地址、不合法的地址与 {@code loopback-only} 模式下的外部地址都返回 null（同旧版抓取失败），不抛出。
 * 只访问本机模拟服务。
 */
class BacklinkFetcherTest {

    private LoopbackMockServer mock;

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private static BacklinkFetcher fetcher(Duration read, int max) {
        return new BacklinkFetcher(new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), read, max, List.of()));
    }

    private static BacklinkFetcher fetcher() {
        return fetcher(Duration.ofSeconds(5), 1 << 16);
    }

    @Test
    void returnsTheBodyForAnyStatusAndFollowsRedirects() {
        mock.stub("GET", "/ok", MockResponse.text(200, "<a href=\"http://www.example.test/\">x</a>"));
        mock.stub("GET", "/gone", MockResponse.text(404, "not here www.example.test"));
        mock.stub("GET", "/moved", MockResponse.redirect(302, "/ok"));
        assertThat(text(fetcher().fetch(mock.uri("/ok").toString()))).contains("www.example.test");
        assertThat(text(fetcher().fetch(mock.uri("/gone").toString()))).isEqualTo("not here www.example.test");
        assertThat(text(fetcher().fetch(" " + mock.uri("/moved") + " "))).contains("www.example.test");
        assertThat(mock.requests()).extracting(r -> r.method() + " " + r.path())
                .containsExactly("GET /ok", "GET /gone", "GET /moved", "GET /ok");
        assertThat(mock.requests().get(0).header("user-agent")).startsWith("Mozilla/5.0 (Windows NT 10.0; WOW64)");
    }

    @Test
    void failuresAreNull() {
        mock.stub("GET", "/slow", MockResponse.text(200, "x").withDelay(Duration.ofSeconds(3)));
        mock.stub("GET", "/big", MockResponse.text(200, "x".repeat(4096)));
        mock.stub("GET", "/loop", MockResponse.redirect(302, "/loop"));
        mock.stub("GET", "/out", MockResponse.redirect(302, "http://203.0.113.9/"));
        assertThat(fetcher(Duration.ofMillis(500), 1 << 16).fetch(mock.uri("/slow").toString())).isNull();
        assertThat(fetcher(Duration.ofSeconds(5), 1024).fetch(mock.uri("/big").toString())).isNull();
        assertThat(fetcher().fetch(mock.uri("/loop").toString())).isNull();
        assertThat(fetcher().fetch(mock.uri("/out").toString())).isNull();
        assertThat(mock.requests()).hasSize(1 + 1 + 6 + 1);
        for (String bad : List.of("http://127.0.0.1:1/", "file:///C:/Windows/win.ini", "ftp://127.0.0.1/", "gopher://127.0.0.1/",
                "http://u:p@127.0.0.1:" + mock.port() + "/ok", "http://a b/", "not a url", "//127.0.0.1/x",
                "http://203.0.113.9/", "http://example.invalid/")) {
            assertThat(fetcher().fetch(bad)).as(bad).isNull();
        }
        assertThat(mock.requests()).hasSize(9);
    }

    private static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
