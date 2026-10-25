package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.LibraryPageFixtures.RICH;
import static com.maccms.backend.site.web.LibraryPageFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 文章、专题、演员、角色、网址、剧情各页（B05，15 文档第 5、7 节）的 MockMvc 测试：content 服务是替身。覆盖文章分页（首/中/末页、
 * 越界取末页、只渲染当前页）、六类正文的富文本过滤（脚本、事件属性、危险 URL、style 删除，合法段落/列表/表格/链接/图片保留）、
 * 文章积分与密码（签名票据）、列表与分页、单条 RSS、ajax_detail 默认关闭。
 */
@WebMvcTest(controllers = {SiteLibraryController.class, SiteContentController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站 & <b>",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.description=站点描述",
        "maccms.site.password-tickets.secret=" + PlaybackFixtures.SECRET})
@Import(SiteConfiguration.class)
class SiteLibraryPagesMvcTest {

    static final String ART = "/index.php/art/detail/id/55.html";

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

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(actors.listCategories()).thenReturn(LibraryPageFixtures.actorTypes());
        when(websites.listCategories()).thenReturn(LibraryPageFixtures.websiteTypes());
        when(articles.getArticle(eq(55L), anyInt(), any())).thenReturn(LibraryPageFixtures.article(55, 3));
        when(articles.getArticle(55L)).thenReturn(LibraryPageFixtures.article(55, 3));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 富文本过滤（07 文档）：脚本、事件属性、javascript:/data: 地址、style 删除；合法富文本保留。 */
    private static void assertSanitized(Document doc) {
        Element content = doc.selectFirst(".detail-content");
        assertThat(content).isNotNull();
        String html = content.html();
        assertThat(html).doesNotContain("<script", "alert('X')", "onclick", "javascript:", "data:image", "style=");
        assertThat(content.select("p b").text()).isEqualTo("重点");
        assertThat(content.select("ul li").text()).isEqualTo("列表");
        assertThat(content.select("table td").text()).isEqualTo("单元格");
        assertThat(content.select("a[href=https://ok.example.invalid/x]")).hasSize(1);
        assertThat(content.select("img[src=/upload/art/ok.jpg]")).hasSize(1);
        assertThat(doc.select("script")).isEmpty();
    }

    // ------------------------------------------------------------------ 文章

    @Test
    void articleRendersOnlyTheCurrentPageWithLegacyPaging() throws Exception {
        MvcResult first = fetch(ART);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Document doc = html(first);
        assertThat(doc.selectFirst(".article-page")).isNotNull();
        assertThat(doc.selectFirst("h1").text()).isEqualTo("长文章 & 测试");
        assertThat(doc.selectFirst(".content-detail__section-title").text()).isEqualTo("第1页标题");
        assertThat(body(first)).contains("PAGE-1-BODY").doesNotContain("PAGE-2-BODY", "PAGE-3-BODY");
        assertSanitized(doc);
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test" + ART);
        assertThat(doc.selectFirst(".article-pager .pagination__status").text()).isEqualTo("第 1 / 3 页");
        assertThat(doc.selectFirst(".article-pager a[rel=next]").attr("href"))
                .isEqualTo("/index.php/art/detail/id/55/page/2.html");
        assertThat(doc.select(".article-pager a[rel=prev]")).isEmpty();
        // 中间页
        Document middle = html(fetch("/index.php/art/detail/id/55/page/2.html"));
        assertThat(middle.text()).contains("PAGE-2-BODY").doesNotContain("PAGE-1-BODY");
        assertThat(middle.selectFirst(".article-pager a[rel=prev]").attr("href")).isEqualTo(ART);
        // 越界：显示最后一页，canonical 指向最后一页（旧 mac_label_art_detail，common.php:2708）
        MvcResult beyond = fetch("/index.php/art/detail/id/55/page/9.html");
        assertThat(beyond.getResponse().getStatus()).isEqualTo(200);
        Document last = html(beyond);
        assertThat(last.text()).contains("PAGE-3-BODY");
        assertThat(last.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/art/detail/id/55/page/3.html");
        assertThat(last.select(".article-pager a[rel=next]")).isEmpty();
        // 截图：地址按 mac_url_img 规范化（相对地址拼站点路径，与旧版相同），结果只会是站内路径或 http/https 地址
        assertThat(doc.select(".screenshot-list img").eachAttr("src")).startsWith("/upload/shot/1.jpg")
                .allSatisfy(src -> assertThat(src).startsWith("/"));
        // 规则不变：前导 0、0 页、查询串都是 404
        for (String p : List.of("/index.php/art/detail/id/55/page/01.html", "/index.php/art/detail/id/55/page/0.html")) {
            assertThat(fetch(p).getResponse().getStatus()).as(p).isEqualTo(404);
        }
        assertThat(mvc.perform(get(ART + "?page=2")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    /** ThinkPHP 兼容模式（?s=，入口路径）：交给内容页 Controller，canonical 指向路径形式。 */
    @Test
    void compatModeOnTheEntryPathReachesTheContentPages() throws Exception {
        MvcResult r = fetch("/index.php?s=art/detail/id/55/page/2.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document doc = html(r);
        assertThat(doc.text()).contains("PAGE-2-BODY").doesNotContain("PAGE-1-BODY");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/art/detail/id/55/page/2.html");
        assertThat(fetch("/index.php?s=/art/detail/id/55.html").getResponse().getStatus()).isEqualTo(200);
        assertThat(fetch("/index.php?s=art/detail/id/055.html").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void singlePageArticleHasNoPagerAndLongArticlesCapThePager() throws Exception {
        when(articles.getArticle(eq(56L), anyInt(), any())).thenReturn(LibraryPageFixtures.article(56, 1));
        assertThat(html(fetch("/index.php/art/detail/id/56.html")).select(".article-pager")).isEmpty();
        when(articles.getArticle(eq(57L), anyInt(), any())).thenReturn(LibraryPageFixtures.article(57, 1200));
        Document doc = html(fetch("/index.php/art/detail/id/57/page/1000.html"));
        assertThat(doc.selectFirst(".pagination__status").text()).isEqualTo("第 1000 / 1000 页");
        assertThat(doc.text()).contains("PAGE-1000-BODY");
    }

    @Test
    void articlePointsAndPasswordAreFixed403Pages() throws Exception {
        when(articles.getArticle(eq(60L), anyInt(), any())).thenThrow(new ApiException(ErrorCode.ARTICLE_POINTS_REQUIRED, "x"));
        when(articles.requiredPoints(60L)).thenReturn(java.util.Optional.of(6));
        MvcResult points = fetch("/index.php/art/detail/id/60.html");
        assertThat(points.getResponse().getStatus()).isEqualTo(403);
        assertThat(points.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        // B10：旧版 3003 的确认文字；游客只有登录链接、没有购买表单（19 文档第 5.2 节）
        assertThat(html(points).selectFirst(".access-notice__message").text())
                .isEqualTo("观看此数据，需要支付【6】积分，确认支付吗？");
        assertThat(html(points).select("form.content-action")).isEmpty();
        assertThat(html(points).selectFirst(".buy-confirm__login a").attr("href")).isEqualTo("/index.php/user/login.html");

        String pwd = "artpwd-SYNTH";
        when(articles.getArticle(eq(61L), anyInt(), isNull())).thenThrow(new ApiException(ErrorCode.ARTICLE_PASSWORD_REQUIRED, "x"));
        when(articles.getArticle(eq(61L), anyInt(), any(PasswordProof.class))).thenReturn(LibraryPageFixtures.article(61, 1));
        when(articles.verifyPassword(61L, pwd)).thenReturn(true);
        String path = "/index.php/art/detail/id/61.html";
        MvcResult form = fetch(path);
        assertThat(form.getResponse().getStatus()).isEqualTo(403);
        assertThat(html(form).selectFirst("form.password-page__form").attr("action")).isEqualTo(path);
        assertThat(body(form)).doesNotContain("PAGE-1-BODY");
        MvcResult wrong = mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("pwd", "x"))
                .andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(403);
        MvcResult ok = mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("pwd", pwd))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(303);
        String setCookie = ok.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("maccms_pwd_a1_61=").contains("HttpOnly", "SameSite=Lax").doesNotContain(pwd);
        String ticket = setCookie.substring("maccms_pwd_a1_61=".length(), setCookie.indexOf(';'));
        MvcResult read = mvc.perform(get(path).cookie(new Cookie("maccms_pwd_a1_61", ticket))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<PasswordProof> proof = ArgumentCaptor.forClass(PasswordProof.class);
        verify(articles, org.mockito.Mockito.atLeastOnce()).getArticle(eq(61L), anyInt(), proof.capture());
        assertThat(proof.getAllValues()).anySatisfy(p -> assertThat(p).isNotNull());
        // 视频播放的票据不能用于文章
        assertThat(mvc.perform(get(path).cookie(new Cookie("maccms_pwd_v4_61", ticket))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    void articleListsUseVisibleCategoriesAndPaging() throws Exception {
        when(articles.listArticles(any(ContentListQuery.class))).thenAnswer(inv -> {
            ContentListQuery q = inv.getArgument(0);
            return page(List.of(LibraryPageFixtures.artSummary(55), LibraryPageFixtures.artSummary(56)), q.page(), 45);
        });
        Document index = html(fetch("/index.php/art/index.html"));
        assertThat(index.selectFirst("h1").text()).isEqualTo("文章");
        assertThat(index.select(".content-grid .content-card")).hasSize(2);
        assertThat(index.select(".content-card__title").eachText()).containsExactly("文章 55 & <i>", "文章 56 & <i>");
        assertThat(index.select(".content-card a").eachAttr("href")).containsExactly(
                "/index.php/art/detail/id/55.html", "/index.php/art/detail/id/56.html");
        assertThat(index.selectFirst("a[rel=next]").attr("href")).isEqualTo("/index.php/art/index/page/2.html");
        Document type = html(fetch("/index.php/art/type/id/22/page/2.html"));
        assertThat(type.select(".breadcrumb__item").eachText()).containsExactly("首页", "资讯", "国内");
        assertThat(type.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/art/type/id/22/page/2.html");
        assertThat(type.select(".category-grid__link--current").eachText()).containsExactly("资讯");
        ArgumentCaptor<ContentListQuery> q = ArgumentCaptor.forClass(ContentListQuery.class);
        verify(articles, org.mockito.Mockito.atLeastOnce()).listArticles(q.capture());
        assertThat(q.getAllValues()).anySatisfy(x -> {
            assertThat(x.categoryId()).isEqualTo(22);
            assertThat(x.page()).isEqualTo(2);
            assertThat(x.pageSize()).isEqualTo(VideoListQuery.DEFAULT_PAGE_SIZE);
        });
        // 越界页、不可见分类：404
        assertThat(fetch("/index.php/art/index/page/4.html").getResponse().getStatus()).isEqualTo(404);
        assertThat(fetch("/index.php/art/type/id/99.html").getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 其余五类

    @Test
    void everyContentTypeSanitizesItsRichText() throws Exception {
        when(topics.getTopic(7)).thenReturn(LibraryPageFixtures.topic(7));
        when(actors.getActor(8)).thenReturn(LibraryPageFixtures.actor(8));
        when(roles.getRole(9)).thenReturn(LibraryPageFixtures.role(9));
        when(websites.getWebsite(10)).thenReturn(LibraryPageFixtures.website(10, "https://site.example.invalid/"));
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        when(plots.getPlots(9)).thenReturn(LibraryPageFixtures.plots(9, 3));
        for (String[] c : List.of(new String[] {"/index.php/topic/detail/id/7.html", "topic-page"},
                new String[] {"/index.php/actor/detail/id/8.html", "actor-page"},
                new String[] {"/index.php/role/detail/id/9.html", "role-page"},
                new String[] {"/index.php/website/detail/id/10.html", "website-page"},
                new String[] {"/index.php/plot/detail/id/9.html", "plot-page"},
                new String[] {"/index.php/vod/plot/id/9.html", "plot-page"})) {
            MvcResult r = fetch(c[0]);
            assertThat(r.getResponse().getStatus()).as(c[0]).isEqualTo(200);
            Document doc = html(r);
            assertThat(doc.selectFirst("article." + c[1])).as(c[0]).isNotNull();
            assertSanitized(doc);
            assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test" + c[0]);
        }
        Document topic = html(fetch("/index.php/topic/detail/id/7.html"));
        assertThat(topic.select(".video-card__link").eachAttr("href")).containsExactly("/index.php/vod/detail/id/9.html");
        assertThat(topic.select(".topic-list .content-card a").eachAttr("href"))
                .containsExactly("/index.php/art/detail/id/55.html");
        Document website = html(fetch("/index.php/website/detail/id/10.html"));
        Element ext = website.selectFirst(".content-detail__external a");
        assertThat(ext.attr("href")).isEqualTo("https://site.example.invalid/");
        assertThat(ext.attr("rel")).contains("nofollow", "noopener", "noreferrer");
        when(websites.getWebsite(11)).thenReturn(LibraryPageFixtures.website(11, "javascript:alert(1)"));
        assertThat(html(fetch("/index.php/website/detail/id/11.html")).select(".content-detail__external")).isEmpty();
        Document role = html(fetch("/index.php/role/detail/id/9.html"));
        assertThat(role.select(".topic-list a").eachAttr("href")).containsExactly("/index.php/vod/detail/id/9.html");
    }

    @Test
    void plotPagesListPartsAndRenderOnlyTheCurrentOne() throws Exception {
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        when(plots.getPlots(9)).thenReturn(LibraryPageFixtures.plots(9, 3));
        Document doc = html(fetch("/index.php/vod/plot/id/9/page/2.html"));
        assertThat(doc.select(".plot-list a").eachAttr("href")).containsExactly("/index.php/vod/plot/id/9.html",
                "/index.php/vod/plot/id/9/page/2.html", "/index.php/vod/plot/id/9/page/3.html");
        assertThat(doc.select(".plot-list a[aria-current=page]").eachText()).containsExactly("第2集剧情");
        assertThat(doc.text()).contains("PLOT-2").doesNotContain("PLOT-1", "PLOT-3");
        Document beyond = html(fetch("/index.php/plot/detail/id/9/page/7.html"));
        assertThat(beyond.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/plot/detail/id/9/page/3.html");
        // 视频详情的 404/密码/版权规则沿用（详情页的既有行为）
        when(content.getVideo(10)).thenThrow(new ApiException(ErrorCode.VIDEO_NOT_FOUND, "x"));
        assertThat(fetch("/index.php/vod/plot/id/10.html").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void otherListsAndVideoRoles() throws Exception {
        when(topics.listTopics(any())).thenReturn(page(List.of(LibraryPageFixtures.topicSummary(7)), 1, 1));
        when(actors.listActors(any())).thenReturn(page(List.of(LibraryPageFixtures.actorSummary(8)), 1, 1));
        when(roles.listRoles(any())).thenReturn(page(List.of(LibraryPageFixtures.roleSummary(9)), 1, 1));
        when(websites.listWebsites(any())).thenReturn(page(List.of(LibraryPageFixtures.websiteSummary(10)), 1, 1));
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(List.of(SiteContentFixtures.summary(9,
                "有剧情的视频", "upload/vod/9.jpg", SiteContentFixtures.ref(6, "动作片", 1))), 1, 20, 1));
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        for (String[] c : List.of(new String[] {"/index.php/topic/index.html", "/index.php/topic/detail/id/7.html"},
                new String[] {"/index.php/actor/index.html", "/index.php/actor/detail/id/8.html"},
                new String[] {"/index.php/actor/type/id/31.html", "/index.php/actor/detail/id/8.html"},
                new String[] {"/index.php/role/index.html", "/index.php/role/detail/id/9.html"},
                new String[] {"/index.php/website/index.html", "/index.php/website/detail/id/10.html"},
                new String[] {"/index.php/website/type/id/41.html", "/index.php/website/detail/id/10.html"},
                new String[] {"/index.php/plot/index.html", "/index.php/plot/detail/id/9.html"},
                new String[] {"/index.php/vod/role/id/9.html", "/index.php/role/detail/id/9.html"})) {
            MvcResult r = fetch(c[0]);
            assertThat(r.getResponse().getStatus()).as(c[0]).isEqualTo(200);
            assertThat(html(r).select(".content-grid .content-card a").eachAttr("href")).as(c[0]).containsExactly(c[1]);
        }
        ArgumentCaptor<ContentListQuery> q = ArgumentCaptor.forClass(ContentListQuery.class);
        verify(roles, org.mockito.Mockito.atLeastOnce()).listRoles(q.capture());
        assertThat(q.getAllValues()).anySatisfy(x -> assertThat(x.filter().videoId()).isEqualTo(9L));
    }

    // ------------------------------------------------------------------ RSS 与 ajax

    @Test
    void singleItemRssIsEscapedXml() throws Exception {
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        MvcResult r = fetch("/index.php/vod/rss/id/9.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("application/rss+xml");
        String xml = body(r);
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\">")
                .contains("<title>合成站 &amp; &lt;b&gt;</title>")
                .contains("<link>http://maccms.local.test/index.php/vod/detail/id/9.html</link>")
                .contains("<title>流浪地球 &lt;script&gt;alert(1)&lt;/script&gt; &amp; Tom</title>")
                .contains("<pubDate>").doesNotContain("<script").doesNotContain("onclick");
        var parsed = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        assertThat(parsed.select("item")).hasSize(1);
        when(articles.getArticle(62L)).thenThrow(new ApiException(ErrorCode.ARTICLE_PASSWORD_REQUIRED, "x"));
        assertThat(fetch("/index.php/art/rss/id/62.html").getResponse().getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/ajax_detail/id/9.html", "/index.php/art/ajax_detail/id/55.html",
            "/index.php/topic/ajax_detail/id/7.html", "/index.php/plot/ajax_detail/id/9.html"})
    void ajaxDetailIsClosedByDefault(String path) throws Exception {
        assertThat(fetch(path).getResponse().getStatus()).isEqualTo(404);
        verify(content, never()).getVideo(anyLong());
    }
}
