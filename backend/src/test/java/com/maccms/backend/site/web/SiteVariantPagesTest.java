package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.detail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 前台页面的手机模板与语言选择接线（B12 承接 B05 D-B05-8，21 文档第 12.3、12.4 节）：用合成的旧配置（{@code extra/maccms.php}、
 * {@code extra/domain.php}）启动站点，经 {@link SiteVariants#filter()} 与真实的页面控制器、异常处理器请求首页、详情页与 404 页。
 *
 * <p>矩阵：{@code mob_status} 0/1/2 × 手机/桌面 UA × Host（主站、{@code site_wapurl}、带端口的 {@code site_wapurl}、站群）× base-url
 * （根目录、子目录）。每一格都断言：手机 Logo 只在旧 {@code MAC_MOB} 成立时出现；把 Logo 换回主 Logo 后，正文与"桌面 + 主站"逐字节
 * 相同（canonical、站内链接、导航、搜索表单与其余输出都不随手机或 Host 变化）；内容安全策略与 Cache-Control 不变；{@code mob_status}
 * 为 1 或 2 时每个页面带 {@code Vary: User-Agent}，为 0 时不带。语言：{@code app.lang} 为 zh-tw 时旧版 {@code lang()} 的文字（404
 * 页的"页面不存在"）为繁体，新版新增的文字与模板固定文案不变；不受支持的值回退简体。
 */
class SiteVariantPagesTest {

    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    static final String DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36";
    static final String WWW = "www.maccms.test";
    static final String WAP = "m.maccms.test";
    static final String WAP_PORT = "m.maccms.test:8080";
    static final String GROUP = "zone.maccms.test";
    static final List<String> PATHS = List.of("/", "/index.php/vod/detail/id/9.html", "/index.php/vod/detail/id/404.html");

    @TempDir
    Path dir;

    record Response(int status, String cacheControl, String csp, List<String> vary, String body) {
    }

    @ParameterizedTest(name = "mob_status={0} base={1}")
    @CsvSource({"0,https://www.maccms.test/", "1,https://www.maccms.test/", "2,https://www.maccms.test/",
            "0,https://www.maccms.test/sub/", "1,https://www.maccms.test/sub/", "2,https://www.maccms.test/sub/",
            "' 2',https://www.maccms.test/", "01,https://www.maccms.test/", "abc,https://www.maccms.test/"})
    void mobileSelectionOnlySwapsTheLogoAndAddsVary(String mobStatus, String base) throws IOException {
        writeLegacy(mobStatus, "zh-cn");
        int status = phpLooseInt(mobStatus);
        withSite(base, mvc -> {
            for (String path : PATHS) {
                Response baseline = request(mvc, path, DESKTOP, WWW);
                assertThat(baseline.body()).contains("/static/logo.png").doesNotContain("/static/wap.png");
                assertThat(Jsoup.parse(baseline.body()).select("link[rel=canonical]").attr("href"))
                        .satisfiesAnyOf(h -> assertThat(h).isEmpty(), h -> assertThat(h).startsWith(base));
                for (String ua : List.of(IPHONE, DESKTOP)) {
                    for (String host : List.of(WWW, WAP, WAP_PORT, GROUP)) {
                        Response r = request(mvc, path, ua, host);
                        boolean wap = ua.equals(IPHONE) && !host.equals(GROUP)
                                && (status == 2 || (status == 1 && host.equals(WAP)));
                        String where = path + " mob_status=" + mobStatus + " ua=" + (ua.equals(IPHONE) ? "mobile" : "desktop")
                                + " host=" + host;
                        assertThat(r.status()).as(where).isEqualTo(baseline.status());
                        assertThat(r.body().contains("/static/wap.png")).as(where + " 手机 Logo").isEqualTo(wap);
                        assertThat(r.body().replace("/static/wap.png", "/static/logo.png")).as(where + " 其余输出不变")
                                .isEqualTo(baseline.body());
                        assertThat(r.csp()).as(where).isEqualTo(baseline.csp());
                        assertThat(r.cacheControl()).as(where).isEqualTo(baseline.cacheControl());
                        assertThat(r.vary()).as(where + " Vary")
                                .isEqualTo(status == 1 || status == 2 ? List.of("User-Agent") : List.of());
                    }
                }
            }
        });
    }

    @Test
    void zhTwOutputsLegacyLangTextsInTraditionalChinese() throws IOException {
        writeLegacy("2", "zh-tw");
        withSite("https://www.maccms.test/", mvc -> {
            Response notFound = request(mvc, "/index.php/vod/detail/id/404.html", IPHONE, WWW);
            assertThat(notFound.status()).isEqualTo(404);
            var doc = Jsoup.parse(notFound.body());
            // 旧版 lang('page_not_found')
            assertThat(doc.select("h1").text()).isEqualTo("頁面不存在");
            assertThat(doc.title()).isEqualTo("頁面不存在 - 合成旧站");
            // 新版新增的说明文字与模板固定文案不随语言切换（旧模板里写死的文字同样不随 app.lang 变化）
            assertThat(notFound.body()).contains("您访问的页面不存在或已被移除。", "搜索视频");
            Response home = request(mvc, "/", DESKTOP, WWW);
            assertThat(home.status()).isEqualTo(200);
            assertThat(home.body()).contains("合成旧站");
        });
        writeLegacy("2", "en-us");
        withSite("https://www.maccms.test/", mvc -> assertThat(Jsoup.parse(
                request(mvc, "/index.php/vod/detail/id/404.html", DESKTOP, WWW).body()).select("h1").text())
                .as("D-B12-L1：不受支持的语言回退简体").isEqualTo("页面不存在"));
    }

    @Test
    void invalidWapLogoFailsStartupWithoutEchoingTheValue() throws IOException {
        writeLegacy("2", "zh-cn", Map.of("site.site_waplogo", "javascript:alert(1)"));
        new WebApplicationContextRunner()
                .withUserConfiguration(SiteConfiguration.class, SiteContentController.class, SiteExceptionHandler.class)
                .withBean(ContentReadService.class, SiteVariantPagesTest::content)
                .withPropertyValues("maccms.site.enabled=true", "maccms.site.base-url=https://www.maccms.test/",
                        "maccms.legacy-config.dir=" + dir)
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("site_waplogo")
                        .hasMessageNotContaining("javascript"));
    }

    // ------------------------------------------------------------------ 工具

    private void withSite(String base, Consumer<MockMvc> body) {
        new WebApplicationContextRunner()
                .withUserConfiguration(SiteConfiguration.class, SiteContentController.class, SiteExceptionHandler.class)
                .withBean(ContentReadService.class, SiteVariantPagesTest::content)
                .withPropertyValues("maccms.site.enabled=true", "maccms.site.base-url=" + base,
                        "maccms.legacy-config.dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Filter variants = ((FilterRegistrationBean<?>) ctx.getBean("siteVariantFilter")).getFilter();
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(SiteContentController.class))
                            .setControllerAdvice(ctx.getBean(SiteExceptionHandler.class))
                            .addInterceptors(new SiteStatusInterceptor(ctx.getBean(SiteSettings.class)))
                            .addFilters(variants)
                            .build();
                    body.accept(mvc);
                });
    }

    private static Response request(MockMvc mvc, String path, String ua, String host) {
        try {
            MockHttpServletResponse r = mvc.perform(get(path).header("User-Agent", ua).header("Host", host))
                    .andReturn().getResponse();
            return new Response(r.getStatus(), r.getHeader("Cache-Control"), r.getHeader("Content-Security-Policy"),
                    r.getHeaders("Vary"), r.getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** PHP {@code $v == n} 对本测试用到的几种写法（"0"、"1"、"2"、" 2"、"01"、"abc"）的结果。 */
    private static int phpLooseInt(String v) {
        return v.trim().matches("\\d+") ? Integer.parseInt(v.trim()) : -1;
    }

    private void writeLegacy(String mobStatus, String lang) throws IOException {
        writeLegacy(mobStatus, lang, Map.of());
    }

    private void writeLegacy(String mobStatus, String lang, Map<String, String> extra) throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("site.site_name", "合成旧站");
        v.put("site.site_logo", "/static/logo.png");
        v.put("site.site_waplogo", "/static/wap.png");
        v.put("site.site_status", "1");
        v.put("site.site_url", WWW);
        v.put("site.site_wapurl", WAP);
        v.put("site.mob_status", mobStatus);
        v.put("site.template_dir", "default");
        v.put("site.html_dir", "html");
        v.put("site.ads_dir", "ads");
        v.put("site.mob_template_dir", "mobile");
        v.put("site.mob_html_dir", "html");
        v.put("site.mob_ads_dir", "ads");
        v.put("app.lang", lang);
        v.put("rewrite.status", "0");
        v.put("rewrite.suffix_hide", "0");
        v.putAll(extra);
        SiteLegacyConfigPagesTest.writeMaccmsPhp(dir, v);
        // 站群：zone.maccms.test 换模板目录与站名；命中站群时旧版不切手机模板
        String domain = "<?php\nreturn array (\n  '" + GROUP + "' => \n  array (\n    'site_url' => '" + GROUP + "',\n"
                + "    'site_name' => '站群二',\n    'template_dir' => 'zone',\n    'html_dir' => 'html',\n"
                + "    'ads_dir' => 'ads',\n  ),\n);";
        Files.writeString(dir.resolve("extra").resolve("domain.php"), domain, StandardCharsets.UTF_8);
    }

    private static ContentReadService content() {
        ContentReadService content = mock(ContentReadService.class);
        when(content.listCategories()).thenReturn(categories());
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            return page(new ArrayList<>(List.of(summary(100, "片一", "/upload/vod/1.jpg", ref(1, "电影", 0)))), q.page(),
                    q.pageSize(), 1);
        });
        when(content.getVideo(9)).thenReturn(detail(9, "本片", "/upload/vod/9.jpg", "简介", "<p>正文</p>"));
        when(content.getVideo(404)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "视频不存在"));
        return content;
    }
}
