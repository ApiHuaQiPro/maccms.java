package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.site.web.SiteTestRoutes;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 完整应用 + 真实 Tomcat + 原始套接字（不使用 jte 的 Spring Boot starter）：确认缓冲渲染在真实容器中
 * 返回完整响应（Content-Length、非分块），大页面中途失败时是完整 500 而不是 200 + 半页。
 *
 * <p>数据源指向不可达的合成地址（127.0.0.1:1），用于证明 site 请求不会建立任何数据库连接；
 * 测试不连接 maccms_dev 或任何已有库，也不需要数据库环境变量。
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
class SiteRealHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Test
    void placeholderIsCompleteWithContentLengthAndIgnoresHostHeader() throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/_site/placeholder", "Host: evil.example",
                "X-Forwarded-Host: evil.example");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.chunked()).isFalse();
        assertThat(r.complete()).isTrue();
        assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.headers().get("cache-control")).isEqualTo("no-cache");
        assertThat(r.body().stripTrailing()).endsWith("</html>");
        assertThat(Jsoup.parse(r.body()).select("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/_site/placeholder");
        assertThat(r.body()).doesNotContain("evil.example");
    }

    @Test
    void headRequestHasHeadersButNoBody() throws Exception {
        RawHttp.Response get = RawHttp.get(port, "/_site/placeholder");
        RawHttp.Response head = RawHttp.head(port, "/_site/placeholder");
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().get("cache-control")).isEqualTo("no-cache");
        assertThat(head.headers().get("content-length")).isEqualTo(get.headers().get("content-length"));
    }

    @Test
    void largeRenderFailureIsComplete500WithoutPartialPage(CapturedOutput output) throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/__site-test/boom-large", "Cookie: user_check=" + SiteTestRoutes.LEAK);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.chunked()).isFalse();
        assertThat(r.complete()).isTrue();
        assertThat(r.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(r.body().stripTrailing()).contains("data-site-page=\"error\"").endsWith("</html>");
        assertThat(r.body()).doesNotContain(SiteTestRoutes.PARTIAL).doesNotContain(SiteTestRoutes.LEAK);
        assertThat(output.getAll()).contains("site page failed: com.maccms.backend.site.web.PageRenderException")
                .doesNotContain(SiteTestRoutes.LEAK).doesNotContain("synthetic-host");
    }

    @Test
    void sitePagesNeverOpenDatabaseConnections() throws Exception {
        RawHttp.get(port, "/_site/placeholder");
        RawHttp.get(port, "/__site-test/api-error/VIDEO_NOT_FOUND");
        RawHttp.get(port, "/__site-test/fail");
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean()).as("连接池从未初始化").isNull();
    }
}
