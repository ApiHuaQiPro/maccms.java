package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.LEAK;
import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.normalDetail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static com.maccms.backend.site.web.SiteContentFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 三页（MACC-14）的 MockMvc 测试：content 服务是 Mockito 替身（合成数据，不连数据库）。
 * 覆盖正常、空结果、404、参数错误、500/503、恶意文本与 URL、canonical 不受 Host 注入影响、完整缓冲与日志脱敏。
 * 站点关闭见 {@link SiteContentClosedMvcTest}；rewrite 形态见 {@link SiteContentRewriteMvcTest}。
 * 本类显式打开 W2（{@code maccms.browser-external.w2=true}，也是默认值），验证放行时的输出与 MACC-14 相同；关闭时的行为见
 * {@link SiteExternalImagesMvcTest}（B02）。
 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.description=站点描述（合成）",
        "maccms.site.keywords=站点,关键词",
        "maccms.site.image.protocol=https",
        "maccms.browser-external.w2=true"})
@Import(SiteConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SiteContentPagesMvcTest {

    static final String BASE = "http://maccms.local.test/";
    static final String HOME = "/";
    static final String TYPE1 = "/index.php/vod/type/id/1.html";
    static final String TYPE1_P2 = "/index.php/vod/type/id/1/page/2.html";
    static final String DETAIL9 = "/index.php/vod/detail/id/9.html";
    /** 页面里不得出现的旧 PHP 写入口与带副作用的端点（03 方案 6.2）。 */
    static final List<String> FORBIDDEN_LINKS = List.of("ajax/hits", "ajax/digg", "ajax/score", "ajax/pwd",
            "ajax/referer", "ajax/suggest", "user/ajax_ulog", "user/ajax_buy_popedom", "user/visit", "vod/play",
            "vod/player", "vod/down", "comment/", "home.js");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.listCategories()).thenReturn(categories());
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            if (q.categoryId() == null) {
                return page(summaries(100, q.pageSize(), ref(1, "电影", 0)), 1, q.pageSize(), 50);
            }
            return switch (q.categoryId()) {
                case 1 -> page(q.page() <= 3 ? summaries(q.page() * 1000L, Math.min(q.pageSize(), 20),
                        ref(6, "动作片", 1)) : List.of(), q.page(), q.pageSize(), 45);
                case 3 -> page(List.of(), q.page(), q.pageSize(), 0);
                case 6 -> page(List.of(summary(9, "本片", "", ref(6, "动作片", 1)),
                        summary(10, "同类 10", "", ref(6, "动作片", 1))), q.page(), q.pageSize(), 2);
                default -> page(summaries(q.categoryId() * 100L, Math.min(q.pageSize(), 3),
                        ref(q.categoryId(), "其他", 0)), q.page(), q.pageSize(), 3);
            };
        });
        when(content.getVideo(9)).thenReturn(normalDetail());
    }

    private MvcResult call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Document ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertHtml(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isNull();
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("meta[name=robots]")).isEmpty();
        assertThat(doc.select("link[rel=canonical]")).hasSize(1);
        assertThat(doc.select("h1")).hasSize(1);
        return doc;
    }

    /** 完整缓冲的 HTML 响应：Content-Length 等于正文字节数，整页闭合，没有脚本/事件属性/内联样式/禁止的链接。 */
    static void assertHtml(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentType()).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.getResponse().getHeader("Content-Length"))
                .isEqualTo(Integer.toString(r.getResponse().getContentAsByteArray().length));
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'none'");
        String body = body(r);
        assertThat(body.stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");
        Document doc = Jsoup.parse(body);
        assertThat(doc.select("script, style, iframe, object, embed, base, frame")).isEmpty();
        for (Element e : doc.getAllElements()) {
            for (Attribute a : e.attributes()) {
                assertThat(a.getKey()).as("属性 " + a.getKey()).doesNotStartWith("on").isNotEqualTo("style");
            }
        }
        for (Element e : doc.select("[href], [src], [action]")) {
            String url = e.hasAttr("href") ? e.attr("href") : e.hasAttr("src") ? e.attr("src") : e.attr("action");
            if (e.hasClass("skip-link")) {
                assertThat(url).isEqualTo("#main");
                continue;
            }
            assertThat(url).as("URL 只能是站内路径或 http/https").matches("(/(?!/)|https?://).*");
            for (String f : FORBIDDEN_LINKS) {
                assertThat(url).as("禁止链接 " + f).doesNotContain(f);
            }
        }
        assertThat(body).doesNotContain(LEAK).doesNotContain("evil.example");
    }

    private static void assertErrorPage(MvcResult r, int status, String heading) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertHtml(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("[data-site-page=error]")).hasSize(1);
        assertThat(doc.selectFirst("h1").text()).isEqualTo(heading);
        assertThat(doc.select("link[rel=canonical], .site-nav, .video-grid, .video-detail, .category-page")).isEmpty();
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo(status == 503 ? "120" : null);
    }

    private static String canonical(Document doc) {
        return doc.selectFirst("link[rel=canonical]").attr("href");
    }

    // ---------------------------------------------------------------- 首页

    @ParameterizedTest
    @ValueSource(strings = {HOME, "/index.php", "/index.php/"})
    void homeRendersNavigationAndLimitedSummaries(String path) throws Exception {
        Document doc = ok(call(get(path)));
        assertThat(doc.select("[data-site-page=home].site-home")).hasSize(1);
        assertThat(doc.title()).isEqualTo("合成站");
        assertThat(doc.selectFirst("h1").text()).isEqualTo("合成站");
        assertThat(canonical(doc)).isEqualTo(BASE);
        assertThat(doc.select("meta[name=description]").attr("content")).isEqualTo("站点描述（合成）");
        assertThat(doc.select("meta[name=keywords]").attr("content")).isEqualTo("站点,关键词");
        // 页眉导航与分类网格：全部顶级分类，链接由 URL 生成器给出
        assertThat(doc.select(".site-nav a").eachText()).containsExactly("电影", "连续剧", "综艺", "动漫", "纪录片");
        assertThat(doc.select(".site-nav a").eachAttr("href")).containsExactly(TYPE1, "/index.php/vod/type/id/2.html",
                "/index.php/vod/type/id/3.html", "/index.php/vod/type/id/4.html", "/index.php/vod/type/id/5.html");
        assertThat(doc.select("ul.category-grid a")).hasSize(5);
        // 区块：最近更新 + 前 4 个顶级分类中有视频的（综艺无视频，隐藏）
        assertThat(doc.select(".site-home__section-title").eachText()).containsExactly("最近更新", "电影", "连续剧", "动漫");
        Element latest = doc.selectFirst("ul.video-grid");
        assertThat(latest.select("article.video-card")).hasSize(ContentPages.HOME_LATEST_SIZE);
        Element first = latest.selectFirst("article.video-card");
        assertThat(first.selectFirst("a.video-card__link").attr("href")).isEqualTo("/index.php/vod/detail/id/100.html");
        assertThat(first.selectFirst("img").attr("src")).isEqualTo("/upload/vod/100.jpg");
        assertThat(first.selectFirst("img").attr("alt")).isEqualTo("合成视频 100");
        assertThat(doc.select(".site-home__more").eachAttr("href"))
                .containsExactly(TYPE1, "/index.php/vod/type/id/2.html", "/index.php/vod/type/id/4.html");
        // 查询次数有上限：分类 1 次 + 最近更新 1 次 + 至多 4 个分类区块
        verify(content).listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, 12));
        verify(content).listVideos(new VideoListQuery(1, VideoSort.TIME, true, 1, 6));
        verify(content, never()).listVideos(new VideoListQuery(5, VideoSort.TIME, true, 1, 6));
        verify(content, never()).getVideo(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void homeWithoutAnyContentShowsEmptyState() throws Exception {
        when(content.listCategories()).thenReturn(new CategoryListResponse(List.of()));
        doReturn(page(List.of(), 1, 12, 0)).when(content).listVideos(any());
        Document doc = ok(call(get(HOME)));
        assertThat(doc.selectFirst(".site-home__empty").text()).isEqualTo("暂无内容");
        assertThat(doc.select(".video-grid, .site-nav, .category-grid")).isEmpty();
    }

    @Test
    void homeSkipsCategoryBlockDisabledBetweenQueries() throws Exception {
        when(content.listVideos(new VideoListQuery(2, VideoSort.TIME, true, 1, 6)))
                .thenThrow(new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在"));
        Document doc = ok(call(get(HOME)));
        assertThat(doc.select(".site-home__section-title").eachText()).containsExactly("最近更新", "电影", "动漫");
    }

    @Test
    void homeWithQueryStringIs404() throws Exception {
        assertErrorPage(call(get("/?from=" + LEAK)), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    // ---------------------------------------------------------------- 分类页

    @Test
    void categoryFirstPage() throws Exception {
        Document doc = ok(call(get(TYPE1)));
        assertThat(doc.select("[data-site-page=category].category-page")).hasSize(1);
        // title 用分类 SEO 标题（解码一次实体）+ 站名；h1 用分类名
        assertThat(doc.title()).isEqualTo("电影频道 & 精选 - 合成站");
        assertThat(doc.selectFirst("h1").text()).isEqualTo("电影");
        assertThat(doc.select("meta[name=description]").attr("content")).isEqualTo("全部电影 <合成>");
        assertThat(doc.select("meta[name=keywords]").attr("content")).isEqualTo("电影,在线");
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/type/id/1.html");
        assertThat(doc.select(".breadcrumb li").eachText()).containsExactly("首页", "电影");
        assertThat(doc.select(".breadcrumb [aria-current=page]").text()).isEqualTo("电影");
        assertThat(doc.select(".category-page ul.category-grid a").eachText()).containsExactly("动作片", "喜剧片");
        assertThat(doc.select(".category-page ul.category-grid a").eachAttr("href"))
                .containsExactly("/index.php/vod/type/id/6.html", "/index.php/vod/type/id/7.html");
        assertThat(doc.select(".site-nav a[aria-current=page]").text()).isEqualTo("电影");
        assertThat(doc.select("ul.video-grid article.video-card")).hasSize(20);
        // 分页：45 条 → 3 页；第 1 页没有上一页
        assertThat(doc.select("nav.pagination")).hasSize(1);
        assertThat(doc.selectFirst(".pagination__status").text()).isEqualTo("第 1 / 3 页");
        assertThat(doc.select("a.pagination__link--prev")).isEmpty();
        assertThat(doc.selectFirst("a.pagination__link--next").attr("href")).isEqualTo(TYPE1_P2);
        verify(content).listVideos(new VideoListQuery(1, VideoSort.TIME, true, 1, 20));
    }

    @Test
    void categoryLaterPageHasSelfCanonicalAndPrevLink() throws Exception {
        Document doc = ok(call(get(TYPE1_P2)));
        assertThat(doc.title()).isEqualTo("电影频道 & 精选 - 第2页 - 合成站");
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/type/id/1/page/2.html");
        assertThat(doc.selectFirst("a.pagination__link--prev").attr("href")).isEqualTo(TYPE1);
        assertThat(doc.selectFirst("a.pagination__link--next").attr("href"))
                .isEqualTo("/index.php/vod/type/id/1/page/3.html");
        verify(content).listVideos(new VideoListQuery(1, VideoSort.TIME, true, 2, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/type/id/1/page/1.html", "/index.php/vod/type/page/1/id/1.html",
            "/index.php/vod/type/id/1", "/index.php/vod/type/id/1/", "/index.php?s=vod/type/id/1.html",
            "/index.php?s=/vod/type/id/1.html"})
    void nonCanonicalCategoryFormsOutputCanonicalWithoutRedirect(String path) throws Exception {
        Document doc = ok(call(get(path)));
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/type/id/1.html");
    }

    @Test
    void subcategoryBreadcrumbAndTitleFallsBackToName() throws Exception {
        Document doc = ok(call(get("/index.php/vod/type/id/6.html")));
        assertThat(doc.title()).isEqualTo("动作片 - 合成站");
        assertThat(doc.select(".breadcrumb li").eachText()).containsExactly("首页", "电影", "动作片");
        assertThat(doc.select(".breadcrumb a").eachAttr("href")).containsExactly("/", TYPE1);
        // 分类没有 SEO 描述时用站点描述
        assertThat(doc.select("meta[name=description]").attr("content")).isEqualTo("站点描述（合成）");
        assertThat(doc.select("nav.pagination")).isEmpty();
    }

    @Test
    void emptyCategoryFirstPageIs200WithEmptyState() throws Exception {
        Document doc = ok(call(get("/index.php/vod/type/id/3.html")));
        assertThat(doc.selectFirst(".category-page__empty").text()).isEqualTo("该分类暂无内容");
        assertThat(doc.select(".video-grid, nav.pagination")).isEmpty();
        assertThat(doc.select(".site-nav a")).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/type/id/3/page/2.html", "/index.php/vod/type/id/1/page/4.html",
            "/index.php/vod/type/id/1/page/1000.html"})
    void outOfRangePagesAre404(String path) throws Exception {
        assertErrorPage(call(get(path)), 404, "页面不存在");
    }

    @Test
    void unknownCategoryIs404WithoutListing() throws Exception {
        assertErrorPage(call(get("/index.php/vod/type/id/99.html")), 404, "页面不存在");
        verify(content, never()).listVideos(any());
    }

    @Test
    void categoryHiddenBetweenQueriesIs404() throws Exception {
        when(content.listVideos(new VideoListQuery(1, VideoSort.TIME, true, 1, 20)))
                .thenThrow(new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在 " + LEAK));
        assertErrorPage(call(get(TYPE1)), 404, "页面不存在");
    }

    /** 形态认得但参数不合法（INVALID_PARAMETER），或形态不认得（交还 PHP，本地 404）：都不调用服务。 */
    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/type/id/0.html", "/index.php/vod/type/id/01.html",
            "/index.php/vod/type/id/65536.html", "/index.php/vod/type/id/1/page/0.html",
            "/index.php/vod/type/id/1/page/1001.html", "/index.php/vod/type/id/1/page/99999999999.html",
            "/index.php/vod/type/id/1/foo/2.html", "/index.php/vod/type/id/1/id/2.html", "/index.php/vod/type/id.html",
            "/index.php/vod/type/id/1.html?page=2", "/index.php/vod/type/id/1.html?id=2",
            "/index.php/vod/type/id/dongzuo.html", "/index.php/vod/type/id/1.htm", "/index.php/vod/type/ID/1.html",
            "/index.php/vod/type/id/1%2F2.html", "/index.php/vod/type/id/1;jsessionid=x.html",
            "/vod/type/id/1.html", "/index.php/vod/type/../detail/id/9.html", "/index.php/vod/typex/id/1.html",
            "/index.php/vod/type"})
    void invalidOrUnrecognizedCategoryUrlsAre404WithoutQueries(String path) throws Exception {
        MvcResult r = call(get(path));
        if (r.getResponse().getStatus() == 400) {
            // 容器在进入 Spring 之前拒绝的地址（如编码斜线）：同样不会进入页面
            verifyNoInteractions(content);
            return;
        }
        assertThat(r.getResponse().getStatus()).as(path).isIn(404);
        if (r.getResponse().getContentType() != null && r.getResponse().getContentType().startsWith("text/html")) {
            assertErrorPage(r, 404, "页面不存在");
        }
        verifyNoInteractions(content);
    }

    // ---------------------------------------------------------------- 详情页

    @Test
    void detailRendersPublicMetadataSanitizedContentAndNoPlayback() throws Exception {
        MvcResult r = call(get(DETAIL9));
        Document doc = ok(r);
        String html = body(r);
        assertThat(doc.select("article[data-site-page=detail].video-detail")).hasSize(1);
        // 文本：解码一次实体，再按文本转义输出；名称里的 <script> 只是文字
        String name = "流浪地球 <script>alert(1)</script> & Tom";
        assertThat(doc.selectFirst("h1").text()).isEqualTo(name);
        assertThat(doc.title()).isEqualTo(name + " - 合成站");
        assertThat(html).contains("流浪地球 &lt;script&gt;alert(1)&lt;/script&gt; &amp; Tom").doesNotContain("&amp;amp;");
        assertThat(doc.selectFirst(".video-detail__subtitle").text()).isEqualTo("副标题 & 特别篇");
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/detail/id/9.html");
        assertThat(doc.select("meta[name=description]").attr("content")).isEqualTo("一段 简介 & 剧情 第二行");
        assertThat(doc.select("meta[name=keywords]").attr("content")).isEqualTo("标签甲,标签乙");
        assertThat(doc.select(".breadcrumb li").eachText()).containsExactly("首页", "电影", "动作片", name);
        assertThat(doc.select(".site-nav a[aria-current=page]").text()).isEqualTo("电影");
        // 海报经 ImageUrlNormalizer；首屏海报不懒加载
        Element poster = doc.selectFirst(".video-detail__poster img");
        assertThat(poster.attr("src")).isEqualTo("/upload/vod/9.jpg");
        assertThat(poster.attr("alt")).isEqualTo(name);
        // 元数据表
        Element meta = doc.selectFirst("dl.metadata-list");
        assertThat(meta.select("dt").eachText()).containsExactly("分类", "类型", "主演", "导演", "编剧", "地区", "语言",
                "年份", "上映", "片长", "状态", "集数", "标签", "评分", "豆瓣评分", "更新时间");
        assertThat(meta.select("dd").eachText()).contains("演员甲 & 演员乙 <b>粗体</b>", "共 24 集（完结）", "8.6", "7.9",
                "2026-10-01", "动作片");
        assertThat(meta.select("b")).isEmpty();
        // 正文：只输出过滤器产物
        Element body = doc.selectFirst(".detail-content");
        assertThat(body.select("p").first().text()).isEqualTo("正文 & 重点");
        assertThat(body.select("b").text()).isEqualTo("重点");
        assertThat(body.select("img").attr("src")).isEqualTo("https://img.maccms.test/a.jpg");
        assertThat(body.select("a").eachAttr("href")).containsExactly("https://www.maccms.test/x.html");
        assertThat(body.select("a").attr("rel")).isEqualTo("nofollow noopener noreferrer");
        assertThat(body.text()).contains("坏链接").doesNotContain("alert");
        assertThat(html).doesNotContain("javascript:").doesNotContain("steal()").doesNotContain("evil.test");
        // 同类推荐不含本片；标题颜色（style）不输出；没有播放入口
        assertThat(doc.select(".video-detail__section ul.video-grid a.video-card__link").eachAttr("href"))
                .containsExactly("/index.php/vod/detail/id/10.html");
        assertThat(html).doesNotContain("#ff0000").doesNotContain("播放");
        verify(content).listVideos(new VideoListQuery(6, VideoSort.TIME, true, 1, 7));
    }

    @Test
    void detailMaliciousImageAndEmptyContentFallBackSafely() throws Exception {
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.detail(9, "片名", "javascript:alert(1)\" onerror=\"x",
                "简介 &lt;b&gt; 文本", ""));
        Document doc = ok(call(get(DETAIL9)));
        assertThat(doc.select(".video-detail__poster img")).isEmpty();
        assertThat(doc.select(".video-detail__placeholder")).hasSize(1);
        assertThat(doc.selectFirst(".detail-content").text()).isEqualTo("简介 <b> 文本");
        assertThat(doc.select(".detail-content b")).isEmpty();
    }

    @Test
    void detailWithoutContentAndBlurbShowsPlaceholderText() throws Exception {
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.detail(9, "片名", "", "", "<script>x</script>"));
        Document doc = ok(call(get(DETAIL9)));
        assertThat(doc.selectFirst(".detail-content").text()).isEqualTo("暂无简介");
        assertThat(doc.select(".video-detail__poster img")).isEmpty();
    }

    @Test
    void detailCategoryHiddenForRelatedStillRenders() throws Exception {
        when(content.listVideos(new VideoListQuery(6, VideoSort.TIME, true, 1, 7)))
                .thenThrow(new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在"));
        Document doc = ok(call(get(DETAIL9)));
        assertThat(doc.select(".video-detail .video-grid")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/detail/id/9", "/index.php/vod/detail/id/9/",
            "/index.php?s=vod/detail/id/9.html"})
    void nonCanonicalDetailFormsOutputCanonical(String path) throws Exception {
        assertThat(canonical(ok(call(get(path))))).isEqualTo(BASE + "index.php/vod/detail/id/9.html");
    }

    @Test
    void missingVideoIs404() throws Exception {
        when(content.getVideo(8)).thenThrow(new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在 " + LEAK));
        assertErrorPage(call(get("/index.php/vod/detail/id/8.html")), 404, "页面不存在");
        verify(content, never()).listCategories();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIDEO_PASSWORD_REQUIRED", "VIDEO_COPYRIGHT_RESTRICTED"})
    void passwordAndCopyrightStay503WithoutReason(String code, CapturedOutput output) throws Exception {
        when(content.getVideo(9)).thenThrow(new ApiException(ErrorCode.valueOf(code), "合成 " + LEAK));
        MvcResult r = call(get(DETAIL9));
        assertErrorPage(r, 503, "服务暂时不可用");
        assertThat(body(r)).doesNotContain("密码").doesNotContain("版权").doesNotContain("流浪地球");
        assertThat(output.getAll()).contains("site handoff not implemented: " + code).doesNotContain(LEAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/vod/detail/id/0.html", "/index.php/vod/detail/id/4294967296.html",
            "/index.php/vod/detail/id/9/page/2.html", "/index.php/vod/detail/id/9.html?sid=1",
            "/index.php/vod/detail/id/abc.html", "/vod/detail/id/9.html", "/index.php/vod/detail/id/-9.html"})
    void invalidDetailUrlsAre404WithoutQueries(String path) throws Exception {
        assertErrorPage(call(get(path)), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    // ---------------------------------------------------------------- 失败、Host 注入、日志

    @ParameterizedTest
    @ValueSource(strings = {HOME, TYPE1, DETAIL9})
    void databaseUnavailableIs503(String path, CapturedOutput output) throws Exception {
        when(content.listCategories()).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        when(content.getVideo(9)).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        assertErrorPage(call(get(path).header("Cookie", "user_check=" + LEAK)), 503, "服务暂时不可用");
        assertThat(output.getAll()).contains("site page unavailable").doesNotContain(LEAK);
    }

    /** 前面的查询已成功、后面的查询失败：完整 500 页，不含已取到的数据（没有半页），日志无 message。 */
    @ParameterizedTest
    @ValueSource(strings = {HOME, TYPE1, DETAIL9})
    void laterQueryFailureIsComplete500WithoutPartialData(String path, CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("jdbc:mysql://" + LEAK + "/db",
                new java.sql.SQLException("select vod_pwd from mac_vod where x='" + LEAK + "'")))
                .when(content).listVideos(any());
        MvcResult r = call(get(path).header("Cookie", "user_id=1; user_check=" + LEAK)
                .header("Authorization", "Basic " + LEAK));
        assertErrorPage(r, 500, "页面暂时无法显示");
        assertThat(body(r)).doesNotContain("流浪地球").doesNotContain("电影").doesNotContain("Exception");
        assertThat(output.getAll()).contains("site page failed: java.lang.IllegalStateException")
                .contains("Caused by: java.sql.SQLException").doesNotContain(LEAK).doesNotContain("mac_vod")
                .doesNotContain("user_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {HOME, TYPE1, TYPE1_P2, DETAIL9})
    void canonicalAndLinksIgnoreHostAndForwardedHeaders(String path) throws Exception {
        MvcResult r = call(get(path)
                .header("Host", "evil.example")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Prefix", "/evil")
                .header("Forwarded", "host=evil.example;proto=https"));
        Document doc = ok(r);
        assertThat(canonical(doc)).startsWith(BASE);
        assertThat(body(r)).doesNotContain("evil").doesNotContain("https://maccms.local.test");
    }

    @Test
    void richTextIsSanitizedOnceAndCachedPerVersion() throws Exception {
        ok(call(get(DETAIL9)));
        ok(call(get(DETAIL9)));
        // 第二次命中缓存；改正文（同一 vod_time）后重新过滤
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.detail(9, "片名", "", "", "<p>新正文</p>"));
        Document doc = ok(call(get(DETAIL9)));
        assertThat(doc.selectFirst(".detail-content").text()).isEqualTo("新正文");
    }

    @Test
    void videoSummaryWithEntityAndTagsInTitleIsEscapedInCards() throws Exception {
        VideoSummaryDto evil = summary(77, "\"><img src=x onerror=alert(1)> &amp;lt;b&amp;gt;", "//evil.test/a.png",
                ref(2, "连续剧", 0));
        when(content.listVideos(new VideoListQuery(2, VideoSort.TIME, true, 1, 20)))
                .thenReturn(page(List.of(evil), 1, 20, 1));
        MvcResult r = call(get("/index.php/vod/type/id/2.html"));
        Document doc = ok(r);
        Element card = doc.selectFirst("article.video-card");
        assertThat(card.selectFirst(".video-card__title").text()).isEqualTo("\"><img src=x onerror=alert(1)> &lt;b&gt;");
        // 协议相对海报按 base-url 协议补全（06 文档第 2 节）；alt 按属性转义
        assertThat(card.select("img")).hasSize(1);
        assertThat(card.selectFirst("img").attr("src")).isEqualTo("http://evil.test/a.png");
        assertThat(doc.select("img[onerror]")).isEmpty();
    }
}
