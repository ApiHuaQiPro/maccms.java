package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * B05（队长裁定 D-B05-5、D-B05-7）：分类筛选页 vod/show、位置参数 vodsearch/vodshow、ajax_search/ajax_show 片段的 MockMvc 测试
 * （content 为替身）。旧 app.show 与 app.ajax_page 在这里打开；默认关闭时的 404 见 {@link SiteVodListClosedMvcTest}。
 */
@WebMvcTest(controllers = {SiteSearchController.class, SiteVodShowController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.features.show=true",
        "maccms.site.features.ajax-page=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteVodListPagesMvcTest {

    static final String SHOW = "/index.php/vod/show/id/1.html";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.searchEnabled()).thenReturn(true);
        CategoryExtendDto movie = new CategoryExtendDto("动作", "大陆,香港", "国语", "2026", "演员甲", "导演甲", "正片", "高清版");
        CategoryExtendDto child = new CategoryExtendDto("", "美国", "", "", "", "", "", "");
        when(content.listCategories()).thenReturn(new CategoryListResponse(List.of(
                new CategoryDto(1, "电影", "en1", 1, 0, "", "", "", "", "", movie, List.of(
                        new CategoryDto(6, "动作片", "en6", 6, 1, "", "", "", "", "", child, List.of()))),
                new CategoryDto(2, "连续剧", "en2", 2, 0, "", "", "", "", "", null, List.of()))));
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            return page(summaries(q.page() * 100L, q.page() > 3 ? 0 : 20, ref(6, "动作片", 1)), q.page(), q.pageSize(), 45);
        });
    }

    private MvcResult call(String pathAndQuery) throws Exception {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        return mvc.perform(MockMvcRequestBuilders.get(URI.create(path)).with(req -> {
            req.setQueryString(query);
            return req;
        })).andReturn();
    }

    private Document ok(String pathAndQuery) throws Exception {
        MvcResult r = call(pathAndQuery);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return Jsoup.parse(body(r));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private VideoListQuery lastQuery() {
        ArgumentCaptor<VideoListQuery> c = ArgumentCaptor.forClass(VideoListQuery.class);
        verify(content, org.mockito.Mockito.atLeastOnce()).listVideos(c.capture());
        return c.getValue();
    }

    // ---------------------------------------------------------------- vod/show

    @Test
    void showListsTheCategoryAndEveryLinkKeepsIt() throws Exception {
        Document doc = ok(SHOW);
        VideoListQuery q = lastQuery();
        assertThat(q.categoryId()).as("分类本身 + 直接子分类，与 API categoryId 相同（D-B05-7）").isEqualTo(1);
        assertThat(q.legacy().tid()).isNull();
        assertThat(q.filter().isEmpty()).isTrue();
        assertThat(doc.selectFirst("[data-site-page]").attr("data-site-page")).isEqualTo("show");
        assertThat(doc.selectFirst("h1").text()).isEqualTo("电影");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test" + SHOW);
        assertThat(doc.selectFirst("meta[name=robots]").attr("content")).contains("noindex");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo(SHOW);
        assertThat(doc.select("form.search-form input[type=hidden]")).isEmpty();
        // 筛选、排序与分页链接都留在该分类的筛选页
        assertThat(doc.select(".search-filters a").eachAttr("href")).isNotEmpty().allMatch(h -> h.startsWith(SHOW));
        assertThat(doc.selectFirst("nav.pagination a[rel=next]").attr("href")).isEqualTo(SHOW + "?page=2");
        // 候选值来自当前分类的扩展字段；不提供 tid 组
        assertThat(doc.select("ul[aria-labelledby=search-filter-area] a").eachText()).containsExactly("全部", "大陆", "香港");
        assertThat(doc.select("#search-filter-tid")).isEmpty();
        assertThat(doc.select(".search-summary").text()).isEqualTo("共 45 条");
    }

    @Test
    void childCategoryUsesItsOwnExtendAndConditionsRoundTrip() throws Exception {
        Document doc = ok("/index.php/vod/show/id/6.html?area=" + enc("美国") + "&by=hits&page=2");
        VideoListQuery q = lastQuery();
        assertThat(q.categoryId()).isEqualTo(6);
        assertThat(q.filter().area()).isEqualTo("美国");
        assertThat(q.page()).isEqualTo(2);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("动作片");
        String base = "/index.php/vod/show/id/6.html";
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test" + base + "?area=" + enc("美国") + "&by=hits&page=2");
        assertThat(doc.selectFirst("nav.pagination a[rel=prev]").attr("href"))
                .isEqualTo(base + "?area=" + enc("美国") + "&by=hits");
        assertThat(doc.select("form.search-form input[type=hidden]").eachAttr("name")).containsExactly("area", "by");
        assertThat(doc.select("ul[aria-labelledby=search-filter-area] a").eachText()).containsExactly("全部", "美国");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vodshow/6-%E7%BE%8E%E5%9B%BD-hits------2---.html",
        "/index.php/vod/show/area/%E7%BE%8E%E5%9B%BD/by/hits/id/6/page/2.html",
        "/index.php/vod/show/id/6/page/9.html?area=%E7%BE%8E%E5%9B%BD&by=hits&page=2"})
    void legacyInputFormsPointToTheQueryForm(String path) throws Exception {
        Document doc = ok(path);
        assertThat(lastQuery().categoryId()).isEqualTo(6);
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo(
                "http://maccms.local.test/index.php/vod/show/id/6.html?area=%E7%BE%8E%E5%9B%BD&by=hits&page=2");
    }

    @Test
    void unknownCategoryAndMalformedPathsAre404AndBadConditionsAre400() throws Exception {
        assertThat(call("/index.php/vod/show/id/99.html").getResponse().getStatus()).isEqualTo(404);
        for (String p : List.of("/index.php/vod/show.html", "/index.php/vod/show/id/01.html",
                "/index.php/vodshow/1-a-b-c-d-e-f-g-h-i-j-k-l.html", "/index.php/vod/show/id/1.html?s=vod/search")) {
            assertThat(call(p).getResponse().getStatus()).as(p).isEqualTo(404);
        }
        for (String p : List.of("/index.php/vod/show/id/1.html?tid=2", "/index.php/vod/show/id/1/foo/1.html",
                "/index.php/vod/show/id/1.html?level=99", "/index.php/vod/show/id/1.html?page=0")) {
            MvcResult r = call(p);
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(400);
            assertThat(body(r)).contains("搜索条件不合法");
        }
        assertThat(call("/index.php/vod/show/id/1.html?page=9").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void containsMatchConditionsAre400BeforeQueryingWhenSearchIsOff() throws Exception {
        when(content.searchEnabled()).thenReturn(false);
        MvcResult r = call(SHOW + "?class=" + enc("动作"));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        verify(content, org.mockito.Mockito.never()).listVideos(any());
        // 精确条件不受影响
        assertThat(call(SHOW + "?area=" + enc("大陆")).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- vodsearch 位置参数

    @Test
    void positionalSearchIsTheSearchPageWithAQueryCanonical() throws Exception {
        Document doc = ok("/index.php/vodsearch/%E5%85%B3%E9%94%AE%E8%AF%8D-------A------.html");
        assertThat(lastQuery().filter().keyword()).isEqualTo("关键词");
        assertThat(lastQuery().filter().letter()).isEqualTo("A");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/vod/search.html?letter=A&wd=" + enc("关键词"));
        assertThat(doc.selectFirst("[data-site-page]").attr("data-site-page")).isEqualTo("search");
    }

    // ---------------------------------------------------------------- ajax 片段

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/ajax_search.html?wd=x", "/index.php/vod/ajax_show/id/1.html",
        "/index.php/vod/ajax_show/id/1/area/x.html"})
    void ajaxFragmentsHaveNoShellNoScriptsAndTheSameCards(String path) throws Exception {
        MvcResult r = call(path);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(SiteHtmlRenderer.CONTENT_SECURITY_POLICY);
        String html = body(r);
        assertThat(html).doesNotContain("<html", "<head", "<script", "<form", "site-header");
        Document doc = Jsoup.parseBodyFragment(html);
        assertThat(doc.select(".video-grid__item")).hasSize(20);
        assertThat(doc.selectFirst("[data-site-page]").attr("data-site-page")).endsWith("-fragment");
    }

    @Test
    void invalidAjaxConditionsAre400EmptyFragments() throws Exception {
        MvcResult r = call("/index.php/vod/ajax_search.html?foo=1");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).doesNotContain("<html", "foo");
        verifyNoInteractions(content);
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
