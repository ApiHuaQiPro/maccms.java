package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 搜索页的 HTML 结构与可访问性约定（可用 HTML 断言的部分；窄屏溢出、焦点可见性需要浏览器，归 T4）：
 * 唯一 h1、标题不跳级、表单控件都有 label、GET 表单无 JS 可提交、筛选/排序/分页都是普通链接、当前项 aria-current、
 * 错误提示与输入框关联、nav 名称不重复、没有正 tabindex 与伪按钮、样式任务（T4）约定的 class 存在。
 */
@WebMvcTest(controllers = SiteSearchController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteSearchHtmlStructureTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.searchEnabled()).thenReturn(true);
        when(content.listCategories()).thenReturn(SiteSearchFixtures.categories());
        when(content.listVideos(any())).thenAnswer(inv -> SiteSearchFixtures.answer(inv.getArgument(0)));
    }

    private Document render(String pathAndQuery, int status) throws Exception {
        MvcResult r = mvc.perform(SiteSearchPagesMvcTest.raw(pathAndQuery)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({"/index.php/vod/search.html,200", "/index.php/vod/search.html?wd=x&area=%E5%A4%A7%E9%99%86,200",
            "/index.php/vod/search.html?page=2&wd=x,200", "/index.php/vod/search.html?wd=%E6%97%A0%E7%BB%93%E6%9E%9C,200",
            "/index.php/vod/search.html?foo=1,400"})
    void documentStructureAndAccessibility(String path, int status) throws Exception {
        Document doc = render(path, status);
        assertThat(doc.selectFirst("html").attr("lang")).isEqualTo("zh-CN");
        assertThat(doc.title()).isNotBlank();
        assertThat(doc.selectFirst("a, button, input").hasClass("skip-link")).isTrue();
        assertThat(doc.select("main#main")).hasSize(1);
        assertThat(doc.select("h1")).hasSize(1);
        int previous = 1;
        for (Element h : doc.select("h1, h2, h3, h4, h5, h6")) {
            int level = h.tagName().charAt(1) - '0';
            assertThat(level).as("标题层级 " + h.outerHtml()).isLessThanOrEqualTo(previous + 1);
            previous = level;
        }
        // 可见的表单控件都有唯一关联的 label；隐藏字段不需要
        for (Element input : doc.select("input:not([type=hidden])")) {
            assertThat(input.id()).as(input.outerHtml()).isNotBlank();
            assertThat(doc.select("label[for=" + input.id() + "]")).as(input.outerHtml()).hasSize(1);
        }
        assertThat(doc.select("[id]").eachAttr("id")).doesNotHaveDuplicates();
        // 搜索表单：原生 GET 表单 + submit 按钮，无 JS 也能提交；两个 role=search 表单可以区分
        Element form = doc.selectFirst("form.search-form");
        assertThat(form.attr("method")).isEqualTo("get");
        assertThat(form.attr("role")).isEqualTo("search");
        assertThat(form.attr("aria-label")).isEqualTo("搜索视频");
        assertThat(form.select("button[type=submit]")).hasSize(1);
        assertThat(form.selectFirst("input#search-page-wd").attr("maxlength")).isEqualTo("64");
        // 交互元素都是原生 a/button/input，没有正 tabindex、伪按钮、脚本或事件属性
        for (Element e : doc.select("[tabindex]")) {
            assertThat(Integer.parseInt(e.attr("tabindex"))).isLessThanOrEqualTo(0);
        }
        assertThat(doc.select("[role=button], [role=link], script, [onclick]")).isEmpty();
        for (Element a : doc.select("a")) {
            assertThat(a.attr("href")).as(a.outerHtml()).isNotBlank();
            String name = a.text().isBlank() && a.selectFirst("img[alt]") != null ? a.selectFirst("img[alt]").attr("alt")
                    : a.text();
            assertThat(name).as("链接名称 " + a.outerHtml()).isNotBlank();
        }
        for (Element nav : doc.select("nav")) {
            assertThat(nav.attr("aria-label")).isNotBlank();
        }
        assertThat(doc.select("nav").eachAttr("aria-label")).doesNotHaveDuplicates();
        // 筛选组：每组的列表由组名标注；列表语义
        for (Element list : doc.select(".filter-group__list")) {
            assertThat(list.tagName()).isEqualTo("ul");
            assertThat(list.children()).allMatch(li -> li.tagName().equals("li"));
            assertThat(doc.select("#" + list.attr("aria-labelledby"))).hasSize(1);
        }
        // aria-current 只用 true（筛选）或 page（导航、分页状态），当前筛选另有 class 标识（不只靠颜色由样式任务处理）
        for (Element e : doc.select("[aria-current]")) {
            assertThat(e.attr("aria-current")).isIn("true", "page");
            if (e.attr("aria-current").equals("true")) {
                assertThat(e.hasClass("filter-chip--current")).isTrue();
            }
        }
    }

    @Test
    void invalidPageAssociatesErrorWithInput() throws Exception {
        Document doc = render("/index.php/vod/search.html?wd=%FF", 400);
        Element error = doc.selectFirst("#search-page-error");
        assertThat(error.text()).contains("搜索条件不合法");
        assertThat(doc.selectFirst("#search-page-wd").attr("aria-describedby")).isEqualTo("search-page-error");
    }

    @Test
    void semanticClassesForStyleTaskArePresent() throws Exception {
        Document results = render("/index.php/vod/search.html?wd=x&area=%E5%A4%A7%E9%99%86", 200);
        for (String cls : List.of("search-page", "search-summary", "search-filters", "filter-group", "filter-chip",
                "filter-chip--current", "search-form", "video-grid", "video-card", "pagination")) {
            assertThat(results.select("." + cls)).as(cls).isNotEmpty();
        }
        Document empty = render("/index.php/vod/search.html?wd=%E6%97%A0%E7%BB%93%E6%9E%9C", 200);
        assertThat(empty.select(".search-empty")).hasSize(1);
    }
}
