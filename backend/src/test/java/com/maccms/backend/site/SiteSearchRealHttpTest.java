package com.maccms.backend.site;

import static com.maccms.backend.site.web.SiteContentFixtures.LEAK;
import static com.maccms.backend.site.web.SiteSearchFixtures.SECRET_KEYWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.web.SiteSearchFixtures;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 搜索页在完整应用 + 真实 Tomcat 下的响应（原始套接字，查询串原样发送）：200/400/404/503/500 都是完整缓冲的 HTML，
 * HEAD 无正文、头相同，noindex/no-store，canonical 不受 Host 影响；非法百分号编码由解析器自己判 400（容器没有因为
 * 解析参数而失败）；日志不含关键字与查询串。content 服务是合成替身，数据源指向不可达地址，连接池从未初始化。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.site.enabled=true",
                "maccms.site.search.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/sub/"})
@ExtendWith(OutputCaptureExtension.class)
class SiteSearchRealHttpTest {

    static final String SEARCH = "/index.php/vod/search.html";

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.searchEnabled()).thenReturn(true);
        when(content.listCategories()).thenReturn(SiteSearchFixtures.categories());
        when(content.listVideos(any())).thenAnswer(inv -> SiteSearchFixtures.answer(inv.getArgument(0)));
    }

    private static void assertComplete(RawHttp.Response r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.chunked()).isFalse();
        assertThat(r.complete()).isTrue();
        assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(r.headers().get("x-robots-tag")).isEqualTo("noindex");
        assertThat(r.headers()).doesNotContainKey("set-cookie");
        assertThat(r.body().stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");
    }

    @ParameterizedTest
    @ValueSource(strings = {SEARCH, SEARCH + "?wd=x", SEARCH + "?area=%E5%A4%A7%E9%99%86&page=2&wd=a%2Fb%5C..%25",
            "/index.php/vod/search/?wd=C%2B%2B", "/index.php/vod/search?wd=a+b&by=hits"})
    void searchPagesAreCompleteAndIgnoreHost(String path) throws Exception {
        RawHttp.Response r = RawHttp.get(port, path, "Host: evil.example", "X-Forwarded-Host: evil.example",
                "X-Forwarded-Proto: https", "Cookie: user_check=" + LEAK);
        assertComplete(r, 200);
        Document doc = Jsoup.parse(r.body());
        assertThat(doc.select("link[rel=canonical]").attr("href")).startsWith("http://maccms.local.test/sub/index.php/vod/search.html");
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo("/sub/index.php/vod/search.html");
        assertThat(r.body()).doesNotContain("evil.example").doesNotContain(LEAK);
    }

    @Test
    void canonicalUsesSortedSingleEncodedQuery() throws Exception {
        RawHttp.Response r = RawHttp.get(port, SEARCH + "?wd=a%2Fb%5C..%25&page=2&area=%e5%a4%a7%e9%99%86");
        assertComplete(r, 200);
        assertThat(Jsoup.parse(r.body()).select("link[rel=canonical]").attr("href")).isEqualTo(
                "http://maccms.local.test/sub/index.php/vod/search.html?area=%E5%A4%A7%E9%99%86&page=2&wd=a%2Fb%5C..%25");
    }

    @Test
    void headHasSameHeadersWithoutBody() throws Exception {
        RawHttp.Response get = RawHttp.get(port, SEARCH + "?wd=x");
        RawHttp.Response head = RawHttp.head(port, SEARCH + "?wd=x");
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().get("content-length")).isEqualTo(get.headers().get("content-length"));
        assertThat(head.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(head.headers().get("x-robots-tag")).isEqualTo("noindex");
        RawHttp.Response badHead = RawHttp.head(port, SEARCH + "?wd=%FF");
        assertThat(badHead.status()).isEqualTo(400);
        assertThat(badHead.body()).isEmpty();
    }

    /** 非法百分号编码：Tomcat 不解析查询串参数（控制器不调用 getParameter），由解析器判 400，页面不回显。 */
    @ParameterizedTest
    @ValueSource(strings = {"?wd=%FF", "?wd=%E4%B8", "?wd=%", "?wd=%ZZ", "?foo=" + LEAK, "?wd=&wd=" + LEAK,
            "?page=01", "?level=00000000009", "?wd=%%EF%BC%94%EF%BC%91"})
    void invalidParametersAreComplete400(String query, CapturedOutput output) throws Exception {
        RawHttp.Response r = RawHttp.get(port, SEARCH + query);
        assertComplete(r, 400);
        Document doc = Jsoup.parse(r.body());
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索条件不合法");
        assertThat(doc.select("link[rel=canonical]")).isEmpty();
        assertThat(r.body()).doesNotContain(LEAK);
        assertThat(output.getAll()).doesNotContain(LEAK);
    }

    /**
     * %HH 只认 ASCII 十六进制。全角、阿拉伯-印度数字原样（UTF-8 字节）放进请求行时，Tomcat 先以 400 拒绝，请求到不了
     * 控制器与 content；解析器自身对这些字符的拒绝见 LegacyUrlParserSearchTest#percentEscapeDigitsMustBeAsciiHex。
     */
    @ParameterizedTest
    @ValueSource(strings = {"?wd=%\uFF14\uFF11", "?wd=%\u0664\u0661"})
    void rawNonAsciiInRequestLineIsRejectedByContainer(String query) throws Exception {
        RawHttp.Response r = RawHttp.get(port, SEARCH + query);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).doesNotContain("搜索");
        verify(content, never()).listVideos(any());
    }

    @Test
    void handedOffAndOverlongAreComplete404() throws Exception {
        for (String q : new String[] {"?by=rnd", "?s=/vod/detail/id/1", "?tid=1", "?wd=x&" + "&".repeat(2044)}) {
            RawHttp.Response r = RawHttp.get(port, SEARCH + q);
            assertComplete(r, 404);
            assertThat(r.body()).contains("data-site-page=\"error\"");
        }
        assertComplete(RawHttp.get(port, SEARCH + "?page=9&wd=x"), 404);
    }

    @Test
    void unavailableAndFailureAreCompleteErrorPagesWithoutKeywordInLogs(CapturedOutput output) throws Exception {
        String q = "?wd=" + URLEncoder.encode(SECRET_KEYWORD, StandardCharsets.UTF_8);
        doThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用")).when(content).listVideos(any());
        RawHttp.Response unavailable = RawHttp.get(port, SEARCH + q);
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.headers().get("retry-after")).isEqualTo("120");
        assertThat(unavailable.complete()).isTrue();

        doThrow(new IllegalStateException(SECRET_KEYWORD + " " + LEAK)).when(content).listVideos(any());
        RawHttp.Response failed = RawHttp.get(port, SEARCH + q);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.complete()).isTrue();
        assertThat(failed.body()).doesNotContain(SECRET_KEYWORD).doesNotContain(LEAK);
        assertThat(output.getAll()).contains("site page failed").doesNotContain(SECRET_KEYWORD).doesNotContain(LEAK)
                .doesNotContain(URLEncoder.encode(SECRET_KEYWORD, StandardCharsets.UTF_8));
    }

    @Test
    void searchNeverOpensDatabaseConnectionsAndThreePagesStillWork() throws Exception {
        RawHttp.get(port, SEARCH + "?wd=x");
        RawHttp.get(port, SEARCH + "?wd=%FF");
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean()).as("连接池从未初始化").isNull();
        // 三页路由不变；其他方法仍是全局行为
        assertThat(RawHttp.get(port, "/index.php/vod/type/id/1.html?wd=x").status()).isEqualTo(404);
        assertThat(RawHttp.get(port, "/index.php/vod/search/wd/x.html").body()).doesNotContain("data-site-page");
    }
}
