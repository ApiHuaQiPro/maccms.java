package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * site 启用、站点开放时的页面与错误页：状态码、响应头、完整缓冲、转义、canonical 来源、日志脱敏。
 * 站名/描述/关键词故意含 HTML 特殊字符与脚本片段（合成值），验证它们只作为文本输出。
 */
@WebMvcTest(controllers = {SitePlaceholderController.class, SiteTestRoutes.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=<合成站> & \"引号\" '单引号'",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.description=描述 <script>alert(1)</script>",
        "maccms.site.keywords=k1,\"k2\"",
        "maccms.site.icp=ICP 合成 0000 号",
        "maccms.site.logo-url=/static/logo.png"})
@Import({SiteConfiguration.class, SiteTestRoutes.class})
@ExtendWith(OutputCaptureExtension.class)
class SitePageMvcTest {

    static final String NAME = "<合成站> & \"引号\" '单引号'";
    static final String CANONICAL = "http://maccms.local.test/_site/placeholder";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        SiteTestRoutes.INVOCATIONS.set(0);
    }

    private MvcResult call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertCommonHtmlHeaders(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentType()).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.getResponse().getHeader("Content-Length"))
                .isEqualTo(Integer.toString(r.getResponse().getContentAsByteArray().length));
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'none'");
        assertThat(body(r).stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");
        assertNoActiveContent(Jsoup.parse(body(r)));
    }

    /** 页面中没有脚本、事件属性、内联样式。 */
    private static void assertNoActiveContent(Document doc) {
        assertThat(doc.select("script, style, iframe, object, embed, base")).isEmpty();
        for (Element e : doc.getAllElements()) {
            for (Attribute a : e.attributes()) {
                assertThat(a.getKey()).as("属性 " + a.getKey()).doesNotStartWith("on").isNotEqualTo("style");
            }
        }
    }

    @Test
    void placeholderPageIsBufferedEscapedAndNoindex() throws Exception {
        MvcResult r = call(get("/_site/placeholder"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertCommonHtmlHeaders(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");

        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("[data-site-page=placeholder]")).hasSize(1);
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        // 特殊字符按文本输出：解析回来的值与配置逐字相等，原文中没有未转义的 <script>
        assertThat(doc.title()).isEqualTo("站点骨架占位页 - " + NAME);
        assertThat(doc.selectFirst(".site-brand__name").text()).isEqualTo(NAME);
        assertThat(doc.select("meta[name=description]").attr("content")).isEqualTo("描述 <script>alert(1)</script>");
        assertThat(doc.select("meta[name=keywords]").attr("content")).isEqualTo("k1,\"k2\"");
        assertThat(body(r)).doesNotContain("<script>").contains("&lt;合成站&gt; &amp;");
        assertThat(doc.selectFirst(".site-footer__icp").text()).isEqualTo("ICP 合成 0000 号");
        assertThat(doc.select("img.site-brand__logo").attr("src")).isEqualTo("/static/logo.png");
        assertThat(doc.select("a.site-brand").attr("href")).isEqualTo("/");
        assertThat(doc.select("link[rel=stylesheet]").attr("href")).isEqualTo("/site/site.css");
    }

    @Test
    void canonicalComesOnlyFromConfiguredBaseUrl() throws Exception {
        MvcResult r = call(get("/_site/placeholder")
                .header("Host", "evil.example")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "host=evil.example;proto=https"));
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("link[rel=canonical]")).hasSize(1);
        assertThat(doc.select("link[rel=canonical]").attr("href")).isEqualTo(CANONICAL);
        assertThat(body(r)).doesNotContain("evil.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIDEO_NOT_FOUND", "CATEGORY_NOT_FOUND", "NOT_FOUND", "INVALID_PARAMETER"})
    void notFoundCodesRenderComplete404Page(String code, CapturedOutput output) throws Exception {
        MvcResult r = call(get("/__site-test/api-error/" + code));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertErrorPage(r, "页面不存在");
        assertThat(r.getResponse().getHeader("Retry-After")).isNull();
        assertThat(output.getAll()).doesNotContain(SiteTestRoutes.LEAK);
    }

    @Test
    void malformedPathVariableIs404Page() throws Exception {
        MvcResult r = call(get("/__site-test/item/abc"));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertErrorPage(r, "页面不存在");
        assertThat(SiteTestRoutes.INVOCATIONS.get()).isZero();
    }

    @Test
    void serviceUnavailableIs503WithRetryAfter(CapturedOutput output) throws Exception {
        MvcResult r = call(get("/__site-test/api-error/SERVICE_UNAVAILABLE"));
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertErrorPage(r, "服务暂时不可用");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("120");
        assertThat(output.getAll()).contains("site page unavailable: com.maccms.backend.common.web.ApiException")
                .doesNotContain(SiteTestRoutes.LEAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIDEO_PASSWORD_REQUIRED", "VIDEO_COPYRIGHT_RESTRICTED"})
    void handoffCodesAre503UntilHandoffIsImplemented(String code, CapturedOutput output) throws Exception {
        MvcResult r = call(get("/__site-test/api-error/" + code));
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertErrorPage(r, "服务暂时不可用");
        assertThat(body(r)).doesNotContain("密码").doesNotContain("版权");
        assertThat(output.getAll()).contains("site handoff not implemented: " + code)
                .doesNotContain(SiteTestRoutes.LEAK);
    }

    @Test
    void unexpectedExceptionIs500WithoutMessageInBodyOrLog(CapturedOutput output) throws Exception {
        MvcResult r = call(get("/__site-test/fail")
                .header("Cookie", "user_id=1; user_check=" + SiteTestRoutes.LEAK)
                .header("Authorization", "Basic " + SiteTestRoutes.LEAK));
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertErrorPage(r, "页面暂时无法显示");
        assertThat(body(r)).doesNotContain(SiteTestRoutes.LEAK).doesNotContain("Exception").doesNotContain("jdbc");
        assertThat(output.getAll())
                .contains("site page failed: java.lang.IllegalStateException")
                .contains("Caused by: java.sql.SQLException")
                .doesNotContain(SiteTestRoutes.LEAK)
                .doesNotContain("synthetic-host")
                .doesNotContain("mac_user")
                .doesNotContain("user_check");
    }

    @Test
    void internalErrorCodeIs500() throws Exception {
        MvcResult r = call(get("/__site-test/api-error/INTERNAL_ERROR"));
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertErrorPage(r, "页面暂时无法显示");
    }

    @Test
    void renderFailureAfterLargeOutputIsComplete500WithoutPartialPage(CapturedOutput output) throws Exception {
        MvcResult r = call(get("/__site-test/boom-large"));
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertErrorPage(r, "页面暂时无法显示");
        assertThat(body(r)).doesNotContain(SiteTestRoutes.PARTIAL).doesNotContain(SiteTestRoutes.LEAK);
        assertThat(output.getAll())
                .contains("site page failed: com.maccms.backend.site.web.PageRenderException")
                .contains("Caused by: java.lang.IllegalStateException")
                .doesNotContain(SiteTestRoutes.LEAK)
                .doesNotContain("synthetic-host");
    }

    @Test
    void nonSiteRoutesKeepGlobalJsonErrors() throws Exception {
        MvcResult r = call(get("/no-such-route"));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(body(r)).contains("\"code\":\"NOT_FOUND\"");
    }

    private static void assertErrorPage(MvcResult r, String heading) throws Exception {
        assertCommonHtmlHeaders(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("[data-site-page=error]")).hasSize(1);
        assertThat(doc.selectFirst("h1").text()).isEqualTo(heading);
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(doc.select("link[rel=canonical]")).isEmpty();
        assertThat(doc.select("meta[name=description]")).isEmpty();
        assertThat(doc.selectFirst(".site-brand__name").text()).isEqualTo(NAME);
    }
}
