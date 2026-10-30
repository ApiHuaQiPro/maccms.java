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
import com.maccms.backend.content.application.LibraryListQuery;
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
        when(search.articles(any())).thenAnswer(inv -> {
            LibraryListQuery q = inv.getArgument(0);
            List<com.maccms.backend.content.application.view.ArticleSummaryDto> items = new ArrayList<>();
            for (int i = 0; i < (q.page() > 2 ? 0 : 20); i++) {
                items.add(LibraryPageFixtures.artSummary(q.page() * 100L + i));
            }
            return LibraryPageFixtures.page(items, q.page(), 45);
        });
        when(search.roles(any())).thenAnswer(inv -> {
            LibraryListQuery q = inv.getArgument(0);
            List<com.maccms.backend.content.application.view.RoleSummaryDto> items = new ArrayList<>();
            for (int i = 0; i < (q.page() > 3 ? 0 : q.page() == 3 ? 8 : 20); i++) {
                items.add(LibraryPageFixtures.roleSummary(q.page() * 100L + i));
            }
            return LibraryPageFixtures.page(items, q.page(), 48);
        });
        when(search.actors(any())).thenReturn(LibraryPageFixtures.page(
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
        ArgumentCaptor<LibraryListQuery> q = ArgumentCaptor.forClass(LibraryListQuery.class);
        verify(search).articles(q.capture());
        assertThat(q.getValue().kind()).isEqualTo(ContentKind.ARTICLE);
        assertThat(q.getValue().categoryId()).isNull();
        assertThat(q.getValue().page()).isEqualTo(2);
        assertThat(q.getValue().pageSize()).isEqualTo(20);
        assertThat(q.getValue().random()).isFalse();
        assertThat(q.getValue().match()).isEqualTo(new LibraryMatch("星", null, null, null, null, 21L, null, "X", null,
                null, null, null, null, null, null));
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
        verify(search, never()).articles(any());
        assertThat(call("/index.php/art/search.html?letter=X").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void showUsesTheModuleCategoryAndUnknownCategoriesAre404() throws Exception {
        Document doc = html(call("/index.php/art/show/id/21.html"));
        ArgumentCaptor<LibraryListQuery> q = ArgumentCaptor.forClass(LibraryListQuery.class);
        verify(search).articles(q.capture());
        assertThat(q.getValue().categoryId()).as("分类本身 + 直接子分类（与列表 API 相同）").isEqualTo(21);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("资讯");
        assertThat(doc.select(".content-list__categories a").eachAttr("href"))
                .containsExactly("/index.php/art/show/id/21.html", "/index.php/art/show/id/23.html");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo("/index.php/art/show/id/21.html");
        assertThat(doc.select(".breadcrumb__item").eachText()).containsExactly("首页", "文章", "资讯");
        for (String p : List.of("/index.php/art/show/id/31.html", "/index.php/art/show/id/99.html",
                "/index.php/topic/show/id/1.html", "/index.php/plot/show/id/1.html",
                "/index.php/art/show/id/21.html?page=9")) {
            assertThat(call(p).getResponse().getStatus()).as(p).isEqualTo(404);
        }
        // 角色筛选页没有分类，不接受 id（与旧 Role 模型一样不读它；未知参数 400）
        assertThat(call("/index.php/role/show/id/1.html").getResponse().getStatus()).isEqualTo(400);
        // 演员筛选页不给分类时取第一个可见分类
        html(call("/index.php/actor/show.html"));
        ArgumentCaptor<LibraryListQuery> a = ArgumentCaptor.forClass(LibraryListQuery.class);
        verify(search).actors(a.capture());
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

    @Test
    void plotSearchReadsTheSameKeysAsTheVideoSearchIncludingTidAndRandom() throws Exception {
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(SiteContentFixtures.summaries(100, 2,
                SiteContentFixtures.ref(6, "动作片", 1)), 3, 20, 45));
        Document doc = html(call("/index.php/plot/search.html?tid=6abc&year=2020&area=%E5%A4%A7%E9%99%86%2C&by=rnd"));
        ArgumentCaptor<VideoListQuery> q = ArgumentCaptor.forClass(VideoListQuery.class);
        verify(content).listVideos(q.capture());
        assertThat(q.getValue().legacy().plot()).isEqualTo(1);
        assertThat(q.getValue().legacy().tid()).as("旧 intval，type_id OR type_id_1").isEqualTo(6L);
        assertThat(q.getValue().random()).isTrue();
        assertThat(q.getValue().filter().year()).isEqualTo("2020");
        assertThat(q.getValue().filter().area()).isEqualTo("大陆,");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test"
                + "/index.php/plot/search.html?area=%E5%A4%A7%E9%99%86%2C&by=rnd&tid=6&year=2020");
        assertThat(doc.select("nav.pagination")).as("随机排序不显示分页").isEmpty();
        assertThat(doc.selectFirst(".search-random-notice").text()).contains("随机排序");
        assertThat(doc.select(".filter-group[aria-labelledby], .filter-chip--current").eachText()).contains("随机");
        assertThat(call("/index.php/plot/search.html?isend=1").getResponse().getStatus())
                .as("isend 经 mac_param_url 读不到，与视频搜索页相同").isEqualTo(400);
    }

    @Test
    void roleShowListsAllPublishedRolesWithTheSearchConditions() throws Exception {
        MvcResult r = call("/index.php/role/show.html?rid=101abc&letter=X%2CF&page=2");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<LibraryListQuery> q = ArgumentCaptor.forClass(LibraryListQuery.class);
        verify(search).roles(q.capture());
        assertThat(q.getValue().categoryId()).isNull();
        assertThat(q.getValue().page()).isEqualTo(2);
        assertThat(q.getValue().match().rid()).isEqualTo(101L);
        assertThat(q.getValue().match().letter()).isEqualTo("X,F");
        Document doc = html(r);
        String base = "/index.php/role/show.html";
        assertThat(doc.selectFirst("[data-site-page]").attr("data-site-page")).isEqualTo("role-show");
        assertThat(doc.selectFirst("h1").text()).isEqualTo("角色");
        assertThat(doc.select(".breadcrumb__item").eachText()).containsExactly("首页", "角色", "筛选");
        assertThat(doc.select(".content-list__categories")).isEmpty();
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test" + base + "?letter=X%2CF&page=2&rid=101");
        assertThat(doc.selectFirst("form.search-form").attr("action")).isEqualTo(base);
        assertThat(doc.selectFirst("nav.pagination a[rel=next]").attr("href"))
                .isEqualTo(base + "?letter=X%2CF&page=3&rid=101");
        assertThat(doc.select(".search-filters a").eachAttr("href")).allMatch(h -> h.startsWith(base));
        assertThat(doc.select(".search-summary").text()).isEqualTo("共 48 条");
        // 无条件：列出全部（与搜索页不同，筛选页无条件也查询）
        assertThat(call(base).getResponse().getStatus()).isEqualTo(200);
        verify(search, org.mockito.Mockito.times(2)).roles(any());
        // ajax 片段与路径参数形式
        MvcResult f = call("/index.php/role/ajax_show.html?rid=101");
        assertThat(f.getResponse().getStatus()).isEqualTo(200);
        assertThat(f.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<form");
        MvcResult path = call("/index.php/role/show/rid/101/letter/X.html");
        assertThat(path.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(path).selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test" + base + "?letter=X&rid=101");
    }

    @Test
    void urlReachableLegacyConditionsAndSortsArePassedThrough() throws Exception {
        call("/index.php/art/search.html?level=1%2C2&letter=X%2CC&by=score&order=asc");
        ArgumentCaptor<LibraryListQuery> q = ArgumentCaptor.forClass(LibraryListQuery.class);
        verify(search).articles(q.capture());
        assertThat(q.getValue().match().level()).as("mac_param_url 的 intval：1,2 为 1").isEqualTo(1L);
        assertThat(q.getValue().match().letter()).isEqualTo("X,C");
        assertThat(q.getValue().descending()).isFalse();

        Document rnd = html(call("/index.php/art/show/id/21.html?by=rnd&page=7"));
        verify(search, org.mockito.Mockito.times(2)).articles(q.capture());
        assertThat(q.getValue().random()).isTrue();
        assertThat(rnd.select("nav.pagination")).isEmpty();
        assertThat(rnd.selectFirst(".search-random-notice")).isNotNull();
        assertThat(rnd.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/art/show/id/21.html?by=rnd&page=7");

        // 负数 level、越界 level 照常查询（旧版无结果），level=all 与 0 为未设置
        assertThat(call("/index.php/art/search.html?wd=x&level=-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(html(call("/index.php/art/search.html?wd=x&level=all")).selectFirst("link[rel=canonical]")
                .attr("href")).endsWith("/index.php/art/search.html?wd=x");
        // 不在该模型旧白名单中的排序：400（旧版回落为 time，已登记差异）
        for (String p : List.of("/index.php/art/search.html?wd=x&by=in", "/index.php/role/search.html?wd=x&by=referer",
                "/index.php/topic/search.html?wd=x&order=up")) {
            assertThat(call(p).getResponse().getStatus()).as(p).isEqualTo(400);
        }
    }
}
