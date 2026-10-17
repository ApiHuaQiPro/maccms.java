package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LibraryMatch;
import com.maccms.backend.content.application.LibrarySearchService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * B05（队长裁定 D-B05-5）模块筛选/搜索页的 MockMvc 测试（content 为替身）：交给 content 的只读调用参数、视图（表单、隐藏字段、筛选组、
 * 链接保留条件）、包含匹配在搜索开关关闭时查库前 400、分类不存在 404、ajax 片段。与旧版的 ID 对照见 ModuleSearchGoldenMySqlIT。
 */
@WebMvcTest(controllers = SiteModuleListController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.features.show=true",
        "maccms.site.features.ajax-page=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteModuleListPagesMvcTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentReadService content;
    @MockitoBean
    ArticleReadService articles;
    @MockitoBean
    TopicReadService topics;
    @MockitoBean
    ActorReadService actors;
    @MockitoBean
    RoleReadService roles;
    @MockitoBean
    WebsiteReadService websites;
    @MockitoBean
    VideoPlotReadService plots;
    @MockitoBean
    LibrarySearchService search;

    @BeforeEach
    void setUp() {
        when(content.searchEnabled()).thenReturn(true);
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(actors.listCategories()).thenReturn(LibraryPageFixtures.actorTypes());
        when(search.articles(any(), any())).thenAnswer(inv -> {
            ContentListQuery q = inv.getArgument(0);
            List<com.maccms.backend.content.application.view.ArticleSummaryDto> items = new ArrayList<>();
            for (int i = 0; i < (q.page() > 2 ? 0 : 20); i++) {
                items.add(LibraryPageFixtures.artSummary(q.page() * 100L + i));
            }
            return LibraryPageFixtures.page(items, q.page(), 45);
        });
        when(search.actors(any(), any())).thenReturn(LibraryPageFixtures.page(
                List.of(LibraryPageFixtures.actorSummary(1)), 1, 1));
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

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void articleSearchPassesTheConditionsAndEveryLinkKeepsThem() throws Exception {
        MvcResult r = call("/index.php/art/search.html?wd=%E6%98%9F&tid=21&letter=X&page=2");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        ArgumentCaptor<ContentListQuery> q = ArgumentCaptor.forClass(ContentListQuery.class);
        ArgumentCaptor<LibraryMatch> m = ArgumentCaptor.forClass(LibraryMatch.class);
        verify(search).articles(q.capture(), m.capture());
        assertThat(q.getValue().kind()).isEqualTo(ContentKind.ARTICLE);
        assertThat(q.getValue().categoryId()).isNull();
        assertThat(q.getValue().page()).isEqualTo(2);
        assertThat(q.getValue().pageSize()).isEqualTo(20);
        assertThat(q.getValue().filter().letter()).isEqualTo("X");
        assertThat(m.getValue()).isEqualTo(new LibraryMatch("星", null, null, null, null, 21L));
        Document doc = html(r);
        String base = "/index.php/art/search.html";
        assertThat(doc.selectFirst("[data-site-page]").attr("data-site-page")).isEqualTo("art-search");
        assertThat(doc.selectFirst(".article-page")).isNotNull();
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索文章：星");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test" + base + "?letter=X&page=2&tid=21&wd=%E6%98%9F");
        assertThat(doc.selectFirst("meta[name=robots]").attr("content")).contains("noindex");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo(base);
        assertThat(doc.select("form.search-form input[type=hidden]").eachAttr("name")).containsExactly("letter", "tid");
        assertThat(doc.selectFirst("#module-search-wd").attr("value")).isEqualTo("星");
        assertThat(doc.select(".filter-group__label").eachText()).containsExactly("首字母", "分类", "排序");
        assertThat(doc.select(".search-filters a").eachAttr("href")).allMatch(h -> h.startsWith(base + "?"))
                .allMatch(h -> h.contains("wd=%E6%98%9F"));
        assertThat(doc.selectFirst("nav.pagination a[rel=prev]").attr("href"))
                .isEqualTo(base + "?letter=X&tid=21&wd=%E6%98%9F");
        assertThat(doc.select(".content-grid .content-card__link").eachAttr("href")).hasSize(20)
                .allMatch(h -> h.startsWith("/index.php/art/detail/id/"));
        assertThat(doc.select(".search-summary").text()).isEqualTo("共 45 条");
    }

    @Test
    void emptySearchShowsOnlyTheFormAndDoesNotQuery() throws Exception {
        Document doc = html(call("/index.php/topic/search.html"));
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索专题");
        assertThat(doc.select(".content-grid")).isEmpty();
        assertThat(call("/index.php/topic/search.html?page=2").getResponse().getStatus()).isEqualTo(404);
        verifyNoInteractions(search);
    }

    @Test
    void containsMatchIs400BeforeQueryingWhenSearchIsOffAndExactFiltersStillWork() throws Exception {
        when(content.searchEnabled()).thenReturn(false);
        for (String p : List.of("/index.php/art/search.html?wd=x", "/index.php/art/search.html?tag=x",
                "/index.php/art/show/id/21.html?class=x", "/index.php/website/search.html?name=x")) {
            MvcResult r = call(p);
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("搜索条件不合法")
                    .doesNotContain("value=\"x\"");
        }
        verify(search, never()).articles(any(), any());
        assertThat(call("/index.php/art/search.html?letter=X").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void showUsesTheModuleCategoryAndUnknownCategoriesAre404() throws Exception {
        Document doc = html(call("/index.php/art/show/id/21.html"));
        ArgumentCaptor<ContentListQuery> q = ArgumentCaptor.forClass(ContentListQuery.class);
        verify(search).articles(q.capture(), any());
        assertThat(q.getValue().categoryId()).as("分类本身 + 直接子分类（与列表 API 相同）").isEqualTo(21);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("资讯");
        assertThat(doc.select(".content-list__categories a").eachAttr("href"))
                .containsExactly("/index.php/art/show/id/21.html", "/index.php/art/show/id/23.html");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo("/index.php/art/show/id/21.html");
        assertThat(doc.select(".breadcrumb__item").eachText()).containsExactly("首页", "文章", "资讯");
        for (String p : List.of("/index.php/art/show/id/31.html", "/index.php/art/show/id/99.html",
                "/index.php/role/show/id/1.html", "/index.php/art/show/id/21.html?page=9")) {
            assertThat(call(p).getResponse().getStatus()).as(p).isEqualTo(404);
        }
        // 演员筛选页不给分类时取第一个可见分类
        html(call("/index.php/actor/show.html"));
        ArgumentCaptor<ContentListQuery> a = ArgumentCaptor.forClass(ContentListQuery.class);
        verify(search).actors(a.capture(), any());
        assertThat(a.getValue().categoryId()).isEqualTo(31);
    }

    @Test
    void ajaxFragmentsHaveNoShell() throws Exception {
        MvcResult r = call("/index.php/art/ajax_search.html?wd=x");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("<html", "<script", "<form", "site-header");
        Document doc = Jsoup.parseBodyFragment(body);
        assertThat(doc.select(".content-card__link")).hasSize(20);
        assertThat(doc.selectFirst("nav.pagination a[rel=next]").attr("href"))
                .as("片段中的分页指向完整页面").isEqualTo("/index.php/art/search.html?page=2&wd=x");
        assertThat(call("/index.php/art/ajax_search.html?foo=1").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void plotSearchListsVideosWithPlots() throws Exception {
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(SiteContentFixtures.summaries(100, 2,
                SiteContentFixtures.ref(6, "动作片", 1)), 1, 20, 2));
        Document doc = html(call("/index.php/plot/search.html?wd=x&by=hits"));
        ArgumentCaptor<VideoListQuery> q = ArgumentCaptor.forClass(VideoListQuery.class);
        verify(content).listVideos(q.capture());
        assertThat(q.getValue().legacy().plot()).isEqualTo(1);
        assertThat(q.getValue().filter().keyword()).isEqualTo("x");
        assertThat(doc.select(".content-card__link").eachAttr("href"))
                .containsExactly("/index.php/plot/detail/id/100.html", "/index.php/plot/detail/id/101.html");
    }
}
