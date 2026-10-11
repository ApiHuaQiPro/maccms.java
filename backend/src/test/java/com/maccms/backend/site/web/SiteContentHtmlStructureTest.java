package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.normalDetail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 三页的 HTML 结构与可访问性约定（03 方案 3.6 中可用 HTML 断言的部分；移动宽度、焦点可见性等需要浏览器的项另行验证）：
 * lang、viewport、唯一 h1、标题层级不跳级、跳到主要内容、图片 alt、链接与表单有可读名称、不使用正 tabindex、
 * 交互元素都是原生 a/button（无 JS 时可用）、Kimi 样式任务约定的语义 class 存在。
 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.logo-url=/static/logo.png"})
@Import(SiteConfiguration.class)
class SiteContentHtmlStructureTest {

    @Autowired
    MockMvc mvc;

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

    private Document render(String path) throws Exception {
        var r = mvc.perform(get(path)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.php/vod/type/id/1/page/2.html", "/index.php/vod/detail/id/9.html"})
    void documentStructureAndAccessibility(String path) throws Exception {
        Document doc = render(path);
        assertThat(doc.selectFirst("html").attr("lang")).isEqualTo("zh-CN");
        assertThat(doc.select("meta[charset]").attr("charset")).isEqualToIgnoringCase("utf-8");
        assertThat(doc.select("meta[name=viewport]").attr("content")).contains("width=device-width");
        assertThat(doc.title()).isNotBlank();

        // 跳到主要内容：第一个可聚焦元素，目标存在
        Element skip = doc.selectFirst("a, button, input");
        assertThat(skip.hasClass("skip-link")).isTrue();
        assertThat(skip.attr("href")).isEqualTo("#main");
        assertThat(doc.select("main#main")).hasSize(1);
        assertThat(doc.select("header.site-header, footer.site-footer")).hasSize(2);

        // 标题：唯一 h1，层级不跳级（正文富文本另计）
        assertThat(doc.select("h1")).hasSize(1);
        int previous = 1;
        for (Element h : doc.select("h1, h2, h3, h4, h5, h6")) {
            if (h.closest(".detail-content") != null) {
                continue;
            }
            int level = h.tagName().charAt(1) - '0';
            assertThat(level).as("标题层级 " + h.outerHtml()).isLessThanOrEqualTo(previous + 1);
            previous = level;
        }

        // 图片都有 alt；装饰性 Logo 为空 alt（站名文字紧随其后）
        for (Element img : doc.select("img")) {
            assertThat(img.hasAttr("alt")).as(img.outerHtml()).isTrue();
            if (!img.hasClass("site-brand__logo")) {
                assertThat(img.attr("alt")).as(img.outerHtml()).isNotBlank();
            }
        }
        // 链接都有 href 与可读名称；没有正 tabindex，也没有用 div/span 冒充的可点击元素
        for (Element a : doc.select("a")) {
            assertThat(a.attr("href")).as(a.outerHtml()).isNotBlank();
            String name = a.text().isBlank() ? a.attr("aria-label") : a.text();
            if (name.isBlank() && a.selectFirst("img[alt]") != null) {
                name = a.selectFirst("img[alt]").attr("alt");
            }
            assertThat(name).as("链接名称 " + a.outerHtml()).isNotBlank();
        }
        for (Element e : doc.select("[tabindex]")) {
            assertThat(Integer.parseInt(e.attr("tabindex"))).as(e.outerHtml()).isLessThanOrEqualTo(0);
        }
        assertThat(doc.select("[role=button], [role=link]")).isEmpty();
        // 表单控件有 label；搜索是普通 GET 表单（无 JS 可用）
        for (Element input : doc.select("input")) {
            assertThat(doc.select("label[for=" + input.id() + "]")).as(input.outerHtml()).hasSize(1);
        }
        Element search = doc.selectFirst("form.site-search");
        assertThat(search.attr("method")).isEqualTo("get");
        assertThat(search.select("button[type=submit]")).hasSize(1);
        // 登录 / 会员中心：链接到旧 PHP，nofollow
        assertThat(doc.selectFirst("a.site-login").attr("rel")).isEqualTo("nofollow");
        // 每个 nav 都有可区分的名称；当前项用 aria-current
        for (Element nav : doc.select("nav")) {
            assertThat(nav.attr("aria-label")).as(nav.outerHtml().substring(0, 60)).isNotBlank();
        }
        assertThat(doc.select("nav").eachAttr("aria-label")).doesNotHaveDuplicates();
        // 列表语义：视频网格、分类网格、导航是 ul/ol > li
        for (Element list : doc.select(".video-grid, .category-grid, .site-nav__list, .breadcrumb__list")) {
            assertThat(list.tagName()).isIn("ul", "ol");
            assertThat(list.children()).allMatch(li -> li.tagName().equals("li"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.php/vod/type/id/1.html", "/index.php/vod/detail/id/9.html"})
    void semanticClassesForStyleTaskArePresent(String path) throws Exception {
        Document doc = render(path);
        List<String> expected = switch (path) {
            case "/" -> List.of("site-home", "category-grid", "video-grid", "video-card");
            case "/index.php/vod/type/id/1.html" ->
                    List.of("category-page", "category-grid", "video-grid", "video-card", "pagination");
            default -> List.of("video-detail", "metadata-list", "detail-content", "video-grid", "video-card");
        };
        for (String cls : expected) {
            assertThat(doc.select("." + cls)).as(cls).isNotEmpty();
        }
    }
}
