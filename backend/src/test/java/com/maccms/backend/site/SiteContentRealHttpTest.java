package com.maccms.backend.site;

import static com.maccms.backend.site.web.SiteContentFixtures.LEAK;
import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.normalDetail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.zaxxer.hikari.HikariDataSource;
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
 * 三页在完整应用 + 真实 Tomcat 下的响应（原始套接字读取）：完整缓冲（Content-Length、非分块）、HEAD 无正文、
 * canonical 不受 Host 影响、错误页完整。content 服务是合成替身，数据源指向不可达的合成地址，
 * 测试结束时连接池从未初始化（页面没有绕过服务直接访问数据库）；不连接 maccms_dev 或任何已有库。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/"})
@ExtendWith(OutputCaptureExtension.class)
class SiteContentRealHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.listCategories()).thenReturn(categories());
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            return page(summaries(q.page() * 100L, q.pageSize(), ref(6, "动作片", 1)), q.page(), q.pageSize(), 90);
        });
        when(content.getVideo(9)).thenReturn(normalDetail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.php", "/index.php/vod/type/id/1.html", "/index.php/vod/type/id/1/page/2.html",
            "/index.php/vod/detail/id/9.html"})
    void pagesAreCompleteBufferedResponses(String path) throws Exception {
        RawHttp.Response r = RawHttp.get(port, path, "Host: evil.example", "X-Forwarded-Host: evil.example",
                "Cookie: user_check=" + LEAK);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.chunked()).isFalse();
        assertThat(r.complete()).isTrue();
        assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.headers().get("cache-control")).isEqualTo("no-cache");
        assertThat(r.headers().get("x-content-type-options")).isEqualTo("nosniff");
        assertThat(r.headers()).doesNotContainKey("set-cookie");
        assertThat(r.body().stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");
        Document doc = Jsoup.parse(r.body());
        assertThat(doc.select("link[rel=canonical]").attr("href")).startsWith("http://maccms.local.test/");
        assertThat(r.body()).doesNotContain("evil.example").doesNotContain(LEAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.php/vod/type/id/1.html", "/index.php/vod/detail/id/9.html"})
    void headHasSameHeadersWithoutBody(String path) throws Exception {
        RawHttp.Response get = RawHttp.get(port, path);
        RawHttp.Response head = RawHttp.head(port, path);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().get("content-length")).isEqualTo(get.headers().get("content-length"));
        assertThat(head.headers().get("cache-control")).isEqualTo("no-cache");
    }

    @Test
    void notFoundAndUnavailableAreCompleteErrorPages(CapturedOutput output) throws Exception {
        when(content.getVideo(anyLong())).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        RawHttp.Response unavailable = RawHttp.get(port, "/index.php/vod/detail/id/9.html");
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.headers().get("retry-after")).isEqualTo("120");
        assertThat(unavailable.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(unavailable.complete()).isTrue();
        assertThat(unavailable.body().stripTrailing()).contains("data-site-page=\"error\"").endsWith("</html>");

        RawHttp.Response missing = RawHttp.get(port, "/index.php/vod/type/id/1/page/1001.html");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.headers().get("x-robots-tag")).isEqualTo("noindex");
        assertThat(missing.complete()).isTrue();
        assertThat(output.getAll()).doesNotContain(LEAK);
    }

    @Test
    void otherMethodsAndUnknownPathsKeepGlobalBehaviour() throws Exception {
        RawHttp.Response css = RawHttp.get(port, "/site/site.css");
        assertThat(css.status()).isEqualTo(200);
        // 未注册的路径不进入 site 页面（RawHttp 固定发送 Accept: text/html，JSON 错误体无法协商，这里只看状态码）
        RawHttp.Response api = RawHttp.get(port, "/api/v1/nope");
        assertThat(api.status()).isEqualTo(404);
        assertThat(api.body()).doesNotContain("data-site-page");
    }

    @Test
    void pagesNeverOpenDatabaseConnections() throws Exception {
        RawHttp.get(port, "/");
        RawHttp.get(port, "/index.php/vod/type/id/1.html");
        RawHttp.get(port, "/index.php/vod/detail/id/9.html");
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean()).as("连接池从未初始化").isNull();
    }
}
