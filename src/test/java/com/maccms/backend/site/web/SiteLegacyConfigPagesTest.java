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
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.SiteConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * B02 旧配置接入的等价性（11 文档第 4.4 节）：同一组站点设置分别经环境变量（{@code maccms.site.*}）与经旧配置
 * {@code extra/maccms.php}（只给 enabled、base-url）注入，首页、分类、详情三页（含非规范地址、404、关站）的状态码、
 * 响应头与正文逐字节相同。W2 打开、关闭两种状态各比一遍；另验证 W2 未设置时与打开时输出相同（默认打开）。
 */
class SiteLegacyConfigPagesTest {

    static final String BASE = "https://www.maccms.test/";

    static final List<String> PATHS = List.of("/", "/index.php", "/index.php/vod/type/id/1.html",
            "/index.php/vod/type/id/1/page/2.html", "/index.php/vod/detail/id/9.html", "/vod/type/id/1.html",
            "/vod/type/id/1/", "/vod/detail/id/9.html", "/vod/detail/id/9/", "/index.php/vod/detail/id/404.html",
            "/index.php/vod/type/id/0.html", "/?from=x");

    @TempDir
    Path dir;

    /** 一组站点设置：旧配置键 → 值，以及与之对应的环境变量配置项 → 值。 */
    record Scenario(String name, Map<String, String> legacy, Map<String, String> env) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Scenario scenario(String name, String... kv) {
        Map<String, String> legacy = new LinkedHashMap<>();
        Map<String, String> env = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            legacy.put(kv[i], kv[i + 1]);
            if (kv[i + 2] != null) {
                env.put(kv[i + 2], kv[i + 1]);
            }
        }
        return new Scenario(name, legacy, env);
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                scenario("默认形态+本地图片",
                        "site.site_name", "旧站名 & <合成>", "maccms.site.name",
                        "site.site_keywords", "关键词甲,关键词乙", "maccms.site.keywords",
                        "site.site_description", "旧站描述 \"引号\"", "maccms.site.description",
                        "site.site_icp", "京ICP证000001", "maccms.site.icp",
                        "site.site_logo", "/static/logo.png", "maccms.site.logo-url",
                        "site.site_status", "1", null,
                        "site.site_close_tip", "临时维护", "maccms.site.closed-message",
                        "rewrite.status", "0", null,
                        "rewrite.suffix_hide", "0", null,
                        "upload.protocol", "https", "maccms.site.image.protocol",
                        "upload.mode", "local", "maccms.site.image.mode",
                        "upload.remoteurl", "http://img.test.com/", "maccms.site.image.remote-url",
                        "upload.img_key", "", "maccms.site.image.img-key",
                        "upload.img_api", "/img.php?url=", "maccms.site.image.img-api"),
                scenario("伪静态+隐藏后缀+远程图片+img_key",
                        "site.site_name", "旧站二", "maccms.site.name",
                        "site.site_icp", "", "maccms.site.icp",
                        "site.site_status", "1", null,
                        "rewrite.status", "1", null,
                        "rewrite.suffix_hide", "1", null,
                        "upload.protocol", "http", "maccms.site.image.protocol",
                        "upload.mode", "remote", "maccms.site.image.mode",
                        "upload.remoteurl", "https://cdn.ext.test/files/", "maccms.site.image.remote-url",
                        "upload.img_key", "img\\.ext\\.test|www\\.maccms\\.test", "maccms.site.image.img-key",
                        "upload.img_api", "/img.php?url=", "maccms.site.image.img-api"),
                scenario("关站",
                        "site.site_name", "旧站三", "maccms.site.name",
                        "site.site_status", "0", null,
                        "site.site_close_tip", "站点临时维护，10分钟后恢复。", "maccms.site.closed-message"));
    }

    /** 没有对应环境变量名的三项（状态与 URL 开关）按旧版语义换算后的环境变量取值。 */
    static Map<String, String> derivedEnv(Scenario s) {
        Map<String, String> env = new LinkedHashMap<>(s.env());
        env.put("maccms.site.status", "0".equals(s.legacy().get("site.site_status")) ? "closed" : "open");
        env.put("maccms.site.url.rewrite", Boolean.toString("1".equals(s.legacy().get("rewrite.status"))));
        env.put("maccms.site.url.suffix-hide", Boolean.toString("1".equals(s.legacy().get("rewrite.suffix_hide"))));
        return env;
    }

    static Stream<Arguments> scenariosAndW2() {
        return scenarios().flatMap(s -> Stream.of(Arguments.of(s, true), Arguments.of(s, false)));
    }

    @ParameterizedTest(name = "{0} W2={1}")
    @MethodSource("scenariosAndW2")
    void legacyConfigRendersTheSameBytesAsEnvironmentVariables(Scenario s, boolean w2) throws IOException {
        writeMaccmsPhp(dir, s.legacy());
        List<String> envProps = new ArrayList<>(List.of("maccms.site.enabled=true", "maccms.site.base-url=" + BASE,
                "maccms.browser-external.w2=" + w2));
        derivedEnv(s).forEach((k, v) -> envProps.add(k + "=" + v));
        List<String> legacyProps = List.of("maccms.site.enabled=true", "maccms.site.base-url=" + BASE,
                "maccms.browser-external.w2=" + w2, "maccms.legacy-config.dir=" + dir);

        List<Response> fromEnv = render(envProps);
        List<Response> fromLegacy = render(legacyProps);

        assertThat(fromLegacy).isEqualTo(fromEnv);
        String home = fromLegacy.get(0).body();
        if ("0".equals(s.legacy().get("site.site_status"))) {
            assertThat(fromLegacy).allSatisfy(r -> assertThat(r.status()).isEqualTo(503));
            assertThat(home).contains(s.legacy().get("site.site_close_tip"));
        } else {
            // 首页、分类（两页）、详情是 200；不认识的地址 404（两边一致）
            assertThat(fromLegacy.subList(0, 5)).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
            assertThat(fromLegacy.subList(9, 12)).allSatisfy(r -> assertThat(r.status()).isEqualTo(404));
            // 防止两边都没取到值而"相同"：页面里确实出现了旧配置的站名
            assertThat(Jsoup.parse(home).title()).contains(s.legacy().get("site.site_name"));
            // W2 打开时外部图片照常输出，关闭时一处都没有
            String all = String.join("\n", fromLegacy.stream().map(Response::body).toList());
            if (w2) {
                assertThat(all).contains("img.ext.test");
            } else {
                assertThat(all).doesNotContain("img.ext.test", "img2.ext.test", "cdn.ext.test", "/img.php?url=");
            }
        }
    }

    @Test
    void w2DefaultsToOpen() {
        List<String> base = List.of("maccms.site.enabled=true", "maccms.site.name=合成站", "maccms.site.base-url=" + BASE);
        List<String> explicit = new ArrayList<>(base);
        explicit.add("maccms.browser-external.w2=true");
        List<String> closed = new ArrayList<>(base);
        closed.add("maccms.browser-external.w2=false");

        List<Response> unset = render(base);
        assertThat(unset).isEqualTo(render(explicit));
        assertThat(unset).isNotEqualTo(render(closed));
        assertThat(unset.get(0).body()).contains("https://img.ext.test/a.jpg");
    }

    record Response(int status, String contentType, String cacheControl, String body) {
    }

    private static List<Response> render(List<String> props) {
        ContentReadService content = content();
        List<Response> out = new ArrayList<>();
        new WebApplicationContextRunner()
                .withUserConfiguration(SiteConfiguration.class, SiteContentController.class, SiteExceptionHandler.class)
                .withBean(ContentReadService.class, () -> content)
                .withPropertyValues(props.toArray(String[]::new))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(SiteContentController.class))
                            .setControllerAdvice(ctx.getBean(SiteExceptionHandler.class))
                            .addInterceptors(new SiteStatusInterceptor(ctx.getBean(SiteSettings.class)))
                            .build();
                    for (String path : PATHS) {
                        MockHttpServletResponse r = mvc.perform(get(path)).andReturn().getResponse();
                        out.add(new Response(r.getStatus(), r.getContentType(), r.getHeader("Cache-Control"),
                                r.getContentAsString(StandardCharsets.UTF_8)));
                    }
                });
        assertThat(out).hasSize(PATHS.size());
        return out;
    }

    private static ContentReadService content() {
        ContentReadService content = mock(ContentReadService.class);
        when(content.listCategories()).thenReturn(categories());
        List<VideoSummaryDto> items = List.of(
                summary(100, "外部图片", "https://img.ext.test/a.jpg", ref(1, "电影", 0)),
                summary(101, "mac 外部", "mac://img2.ext.test/b.jpg", ref(1, "电影", 0)),
                summary(102, "相对路径", "upload/vod/1.jpg", ref(1, "电影", 0)),
                summary(103, "站内路径", "/upload/vod/2.jpg", ref(1, "电影", 0)),
                summary(104, "本站绝对", "https://www.maccms.test/upload/3.jpg", ref(1, "电影", 0)));
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            return page(items, q.page(), q.pageSize(), 45);
        });
        when(content.getVideo(9)).thenReturn(detail(9, "本片", "https://img.ext.test/poster.jpg", "简介",
                "<p>正文</p><img src=\"https://img.ext.test/in-body.jpg\" alt=\"外部\">"
                        + "<img src=\"upload/vod/body.jpg\" alt=\"本站\">"));
        when(content.getVideo(404)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "视频不存在"));
        return content;
    }

    /** 按 var_export 的格式写出只含给定键的 maccms.php（其余 URL 前提取仓库默认值）。 */
    static void writeMaccmsPhp(Path appDir, Map<String, String> values) throws IOException {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.computeIfAbsent("site", k -> new LinkedHashMap<>()).put("install_dir", "/");
        groups.computeIfAbsent("app", k -> new LinkedHashMap<>()).put("pathinfo_depr", "/");
        Map<String, String> rewrite = groups.computeIfAbsent("rewrite", k -> new LinkedHashMap<>());
        rewrite.put("route_status", "0");
        rewrite.put("vod_id", "0");
        rewrite.put("type_id", "0");
        Map<String, String> view = groups.computeIfAbsent("view", k -> new LinkedHashMap<>());
        view.put("index", "0");
        view.put("vod_type", "0");
        view.put("vod_detail", "0");
        values.forEach((k, v) -> {
            String[] p = k.split("\\.", 2);
            groups.computeIfAbsent(p[0], g -> new LinkedHashMap<>()).put(p[1], v);
        });
        StringBuilder php = new StringBuilder("<?php\nreturn array (\n");
        groups.forEach((g, kv) -> {
            php.append("  '").append(g).append("' => \n  array (\n");
            kv.forEach((k, v) -> php.append("    '").append(k).append("' => '").append(quote(v)).append("',\n"));
            php.append("  ),\n");
        });
        php.append(");");
        Files.createDirectories(appDir.resolve("extra"));
        Files.writeString(appDir.resolve("extra").resolve("maccms.php"), php, StandardCharsets.UTF_8);
    }

    private static String quote(String v) {
        return v.replace("\\", "\\\\").replace("'", "\\'");
    }
}
