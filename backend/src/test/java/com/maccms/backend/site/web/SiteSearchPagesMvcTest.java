package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.LEAK;
import static com.maccms.backend.site.web.SiteSearchFixtures.SECRET_KEYWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import org.jsoup.Jsoup;
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
 * SSR 搜索页（MACC-20）的 MockMvc 测试：content 服务是 Mockito 替身（合成数据，不连数据库）。
 * 覆盖 200/400/404/503/500、noindex/no-store、canonical（固定路径 + 查询串、不受 Host 影响）、筛选/排序/分页链接、
 * 只读调用参数、包含匹配开关、日志不含关键字与查询串。请求一律原样设置查询串（不二次编码）；HEAD 见 SiteSearchRealHttpTest。
 */
@WebMvcTest(controllers = {SiteSearchController.class, SiteContentController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.description=站点描述（合成）",
        "maccms.site.image.protocol=https"})
@Import(SiteConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SiteSearchPagesMvcTest {

    static final String BASE = "http://maccms.local.test/";
    static final String SEARCH = "/index.php/vod/search.html";

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

    private MvcResult call(String pathAndQuery) throws Exception {
        return call(raw(pathAndQuery));
    }

    /** 原样设置请求路径与查询串（不经 URI 模板展开或二次编码，非法百分号编码也能发出）。 */
    static MockHttpServletRequestBuilder raw(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        return get(URI.create(path)).with(req -> {
            req.setQueryString(query);
            return req;
        });
    }

    private MvcResult call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 搜索页共同约定：完整 HTML、noindex（头与 meta）、no-store。 */
    private static Document searchPage(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        SiteContentPagesMvcTest.assertHtml(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(doc.select("[data-site-page=search].search-page")).hasSize(1);
        assertThat(doc.select("h1")).hasSize(1);
        return doc;
    }

    private static Document ok(MvcResult r) throws Exception {
        Document doc = searchPage(r, 200);
        assertThat(doc.select("link[rel=canonical]")).hasSize(1);
        return doc;
    }

    private static String canonical(Document doc) {
        return doc.selectFirst("link[rel=canonical]").attr("href");
    }

    private static void assertErrorPage(MvcResult r, int status, String heading) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        SiteContentPagesMvcTest.assertHtml(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        Document doc = Jsoup.parse(body(r));
        assertThat(doc.select("[data-site-page=error]")).hasSize(1);
        assertThat(doc.selectFirst("h1").text()).isEqualTo(heading);
        assertThat(doc.select("link[rel=canonical], .video-grid, .search-page")).isEmpty();
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo(status == 503 ? "120" : null);
    }

    private static List<String> chips(Document doc, String key) {
        return doc.select("ul[aria-labelledby=search-filter-" + key + "] a").eachText();
    }

    // ---------------------------------------------------------------- 无条件

    @ParameterizedTest
    @ValueSource(strings = {SEARCH, "/index.php/vod/search", "/index.php/vod/search/", SEARCH + "?wd=",
            SEARCH + "?wd=0&area=&page=", SEARCH + "?wd=+%E3%80%80+"})
    void withoutConditionsShowsFormWithoutQueries(String path) throws Exception {
        Document doc = ok(call(path));
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索视频");
        assertThat(doc.title()).isEqualTo("搜索视频 - 合成站");
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/search.html");
        Element form = doc.selectFirst("form.search-form");
        assertThat(form.attr("action")).isEqualTo("/index.php/vod/search.html");
        assertThat(form.attr("method")).isEqualTo("get");
        assertThat(form.selectFirst("input[name=wd]").attr("value")).isEmpty();
        assertThat(form.select("input[type=hidden]")).isEmpty();
        assertThat(doc.select(".search-page__hint")).hasSize(1);
        assertThat(doc.select(".search-summary, .search-filters, .video-grid, .site-nav")).isEmpty();
        verifyNoInteractions(content);
    }

    @Test
    void pageWithoutConditionsIs404WithoutQueries() throws Exception {
        assertErrorPage(call(SEARCH + "?page=2"), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    // ---------------------------------------------------------------- 有条件

    @Test
    void keywordSearchRendersResultsFiltersSortAndPagination() throws Exception {
        MvcResult r = call(SEARCH + "?wd=" + "%E5%90%88%E6%88%90" + "&area=%E5%A4%A7%E9%99%86");
        Document doc = ok(r);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索：合成");
        assertThat(doc.title()).isEqualTo("搜索：合成 - 合成站");
        // 非规范（键顺序不同）：照常渲染，canonical 指向规范形态
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/search.html?area=%E5%A4%A7%E9%99%86&wd=%E5%90%88%E6%88%90");
        assertThat(doc.selectFirst(".search-summary").text()).isEqualTo("共 45 条");
        // 表单：回显关键字，其余条件以隐藏字段保留（不含 page）
        Element form = doc.selectFirst("form.search-form");
        assertThat(form.selectFirst("input[name=wd]").attr("value")).isEqualTo("合成");
        assertThat(form.select("input[type=hidden]").eachAttr("name")).containsExactly("area");
        assertThat(form.select("input[type=hidden]").eachAttr("value")).containsExactly("大陆");
        // 结果：只有公开摘要字段（名称按文本转义）
        Element card = doc.selectFirst(".video-grid article.video-card");
        assertThat(card.selectFirst(".video-card__title").text()).isEqualTo("命中 <b>& 片</b>");
        assertThat(card.selectFirst("a").attr("href")).isEqualTo("/index.php/vod/detail/id/9.html");
        assertThat(card.selectFirst("img").attr("src")).isEqualTo("/upload/vod/9.jpg");
        // 导航：查询了分类
        assertThat(doc.select(".site-nav a").eachText()).containsExactly("电影", "连续剧");
        // 筛选候选：可见分类 extend 的并集，去重保持顺序，跳过 ''、'0'、首尾空白、超长、控制字符、格式不合法
        assertThat(chips(doc, "area")).containsExactly("全部", "大陆", "香港", "a&b", "台湾", "日本", "韩国");
        assertThat(chips(doc, "lang")).containsExactly("全部", "国语", "粤语", "日语");
        assertThat(chips(doc, "year")).containsExactly("全部", "2026", "2025", "2024");
        assertThat(chips(doc, "class")).containsExactly("全部", "动作", "喜剧", "剧情/悬疑", "科幻");
        assertThat(chips(doc, "by")).containsExactly("时间", "人气", "评分");
        assertThat(doc.select("ul[aria-labelledby=search-filter-area] a[aria-current=true]").eachText())
                .containsExactly("大陆");
        assertThat(doc.select("ul[aria-labelledby=search-filter-lang] a[aria-current=true]").eachText())
                .containsExactly("全部");
        assertThat(doc.select("ul[aria-labelledby=search-filter-by] a[aria-current=true]").eachText())
                .containsExactly("时间");
        assertThat(doc.select("a.filter-chip--current").size()).isEqualTo(doc.select("a[aria-current=true]").size());
        // 链接：固定路径 + 查询串，键排序，第 1 页不带页码；组合、移除、排序都回到第 1 页
        assertThat(href(doc, "area", "全部")).isEqualTo(SEARCH + "?wd=%E5%90%88%E6%88%90");
        assertThat(href(doc, "area", "a&b")).isEqualTo(SEARCH + "?area=a%26b&wd=%E5%90%88%E6%88%90");
        assertThat(href(doc, "class", "剧情/悬疑"))
                .isEqualTo(SEARCH + "?area=%E5%A4%A7%E9%99%86&class=%E5%89%A7%E6%83%85%2F%E6%82%AC%E7%96%91"
                        + "&wd=%E5%90%88%E6%88%90");
        assertThat(href(doc, "by", "人气")).isEqualTo(SEARCH + "?area=%E5%A4%A7%E9%99%86&by=hits&wd=%E5%90%88%E6%88%90");
        assertThat(doc.selectFirst(".pagination__status").text()).isEqualTo("第 1 / 3 页");
        assertThat(doc.selectFirst("a.pagination__link--next").attr("href"))
                .isEqualTo(SEARCH + "?area=%E5%A4%A7%E9%99%86&page=2&wd=%E5%90%88%E6%88%90");
        // 只读调用：一次分类 + 一次列表（每页 20，默认 time desc）
        verify(content).listCategories();
        verify(content).listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, 20,
                new VideoFilter("合成", "大陆", null, null, null, null)));
        verify(content, never()).getVideo(anyLong());
        assertThat(body(r)).doesNotContain("播放").doesNotContain("vod_pwd").doesNotContain("vod/play");
    }

    private static String href(Document doc, String key, String label) {
        for (Element a : doc.select("ul[aria-labelledby=search-filter-" + key + "] a")) {
            if (a.text().equals(label)) {
                return a.attr("href");
            }
        }
        throw new AssertionError("没有链接 " + key + ":" + label);
    }

    @Test
    void laterPageHasSelfCanonicalPrevNextAndTitle() throws Exception {
        Document doc = ok(call(SEARCH + "?page=2&wd=a%2Fb"));
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/search.html?page=2&wd=a%2Fb");
        assertThat(doc.title()).isEqualTo("搜索：a/b - 第2页 - 合成站");
        assertThat(doc.selectFirst("a.pagination__link--prev").attr("href")).isEqualTo(SEARCH + "?wd=a%2Fb");
        assertThat(doc.selectFirst("a.pagination__link--next").attr("href")).isEqualTo(SEARCH + "?page=3&wd=a%2Fb");
        verify(content).listVideos(new VideoListQuery(null, VideoSort.TIME, true, 2, 20,
                new VideoFilter("a/b", null, null, null, null, null)));
    }

    @Test
    void exactFiltersOnlyShowFilteredHeadingAndSortParameters() throws Exception {
        Document doc = ok(call(SEARCH + "?year=2026&level=09&by=score&order=asc"));
        assertThat(doc.selectFirst("h1").text()).isEqualTo("筛选结果");
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/search.html?by=score&level=9&order=asc&year=2026");
        assertThat(doc.selectFirst("form.search-form").select("input[type=hidden]").eachAttr("name"))
                .containsExactly("by", "level", "order", "year");
        assertThat(doc.select("ul[aria-labelledby=search-filter-by] a[aria-current=true]").eachText())
                .containsExactly("评分");
        verify(content).listVideos(new VideoListQuery(null, VideoSort.SCORE, false, 1, 20,
                new VideoFilter(null, null, null, "2026", 9, null)));
    }

    @Test
    void currentValueOutsideCandidatesIsStillShownAsCurrent() throws Exception {
        Document doc = ok(call(SEARCH + "?area=%E7%81%AB%E6%98%9F"));
        assertThat(chips(doc, "area").subList(0, 2)).containsExactly("全部", "火星");
        assertThat(href(doc, "area", "火星")).isEqualTo(SEARCH + "?area=%E7%81%AB%E6%98%9F");
    }

    @Test
    void emptyFirstPageIs200WithEmptyState() throws Exception {
        Document doc = ok(call(SEARCH + "?wd=%E6%97%A0%E7%BB%93%E6%9E%9C"));
        assertThat(doc.selectFirst(".search-empty").text()).isEqualTo("没有找到相关视频");
        assertThat(doc.selectFirst(".search-summary").text()).isEqualTo("共 0 条");
        assertThat(doc.select(".video-grid, nav.pagination")).isEmpty();
        assertThat(doc.select("form.search-form")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=2&wd=%E6%97%A0%E7%BB%93%E6%9E%9C", "?page=4&wd=x", "?page=1000&wd=x"})
    void outOfRangePagesAre404(String query) throws Exception {
        assertErrorPage(call(SEARCH + query), 404, "页面不存在");
    }

    @Test
    void hostAndForwardedHeadersDoNotAffectCanonicalOrLinks() throws Exception {
        MvcResult r = call(raw(SEARCH + "?wd=x")
                .header("Host", "evil.example")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Prefix", "/evil")
                .header("Forwarded", "host=evil.example;proto=https"));
        Document doc = ok(r);
        assertThat(canonical(doc)).isEqualTo(BASE + "index.php/vod/search.html?wd=x");
        assertThat(body(r)).doesNotContain("evil").doesNotContain("https://maccms.local.test");
    }

    // ---------------------------------------------------------------- 400

    /** 参数不合法：400 搜索错误页，固定文案、空白表单、不回显输入、没有 canonical、不查库。 */
    @ParameterizedTest
    @ValueSource(strings = {"?foo=" + "SYNTHETIC-LEAK-7f3a", "?wd=%FF" + "SYNTHETIC-LEAK-7f3a", "?wd=%E4%B8", "?wd=%",
            "?wd=&wd=SYNTHETIC-LEAK-7f3a", "?page=01", "?page=0", "?level=00000000009", "?level=10",
            "?year=SYNTHETIC-LEAK-7f3a", "?area=a%2Cb", "?by=SYNTHETIC-LEAK-7f3a", "?order=up",
            "?wd=SYNTHETIC-LEAK-7f3axxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "?keyword=x", "?pageSize=5", "?categoryId=1"})
    void invalidParametersAre400WithoutEcho(String query, CapturedOutput output) throws Exception {
        MvcResult r = call(SEARCH + query);
        Document doc = searchPage(r, 400);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索条件不合法");
        assertThat(doc.title()).isEqualTo("搜索条件不合法 - 合成站");
        assertThat(doc.select("#search-page-error")).hasSize(1);
        assertThat(doc.selectFirst("#search-page-wd").attr("aria-describedby")).isEqualTo("search-page-error");
        assertThat(doc.selectFirst("#search-page-wd").hasAttr("value")).isFalse();
        assertThat(doc.select("input[type=hidden], link[rel=canonical], .site-nav, .search-filters, .video-grid"))
                .isEmpty();
        assertThat(body(r)).doesNotContain(LEAK).doesNotContain("keyword").doesNotContain("参数");
        verifyNoInteractions(content);
        assertThat(output.getAll()).doesNotContain(LEAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?wd=x", "?class=x", "?wd=x&area=a"})
    void containsMatchWhileContentSearchDisabledIs400BeforeAnyQuery(String query) throws Exception {
        when(content.searchEnabled()).thenReturn(false);
        Document doc = searchPage(call(SEARCH + query), 400);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索条件不合法");
        verify(content, never()).listCategories();
        verify(content, never()).listVideos(any());
    }

    @Test
    void exactFiltersStillWorkAndClassLinksHiddenWhileContentSearchDisabled() throws Exception {
        when(content.searchEnabled()).thenReturn(false);
        Document doc = ok(call(SEARCH + "?area=%E5%A4%A7%E9%99%86"));
        assertThat(doc.select("ul[aria-labelledby=search-filter-class]")).isEmpty();
        assertThat(chips(doc, "area")).contains("大陆");
    }

    @Test
    void contentInvalidParameterAtQueryTimeIs400() throws Exception {
        // 两次请求之间开关被关闭等：content 抛 INVALID_PARAMETER 时同样是 400 页
        doThrow(ApiException.invalidParameter("keyword", "当前未开放关键字搜索")).when(content).listVideos(any());
        Document doc = searchPage(call(SEARCH + "?wd=x"), 400);
        assertThat(doc.select("link[rel=canonical]")).isEmpty();
    }

    // ---------------------------------------------------------------- 交 PHP（本地 404）

    @ParameterizedTest
    @ValueSource(strings = {"?s=/vod/detail/id/1", "?actor=x", "?tid=1", "?by=rnd", "?wd=x&letter=A"})
    void handedOffQueriesAre404WithoutQueries(String query) throws Exception {
        assertErrorPage(call(SEARCH + query), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    /** 路径参数形式（旧 mac_url_search 的输出）不注册，落到全局 404。 */
    @Test
    void pathParameterFormIsNotRegistered() throws Exception {
        MvcResult r = call("/index.php/vod/search/wd/x.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(r)).doesNotContain("data-site-page");
        verifyNoInteractions(content);
    }

    @Test
    void overlongQueryIsHandedOff() throws Exception {
        assertErrorPage(call(SEARCH + "?wd=x&" + "&".repeat(2044)), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    @Test
    void rewriteFormsAre404WhenRewriteIsOff() throws Exception {
        assertErrorPage(call("/vod/search.html?wd=x"), 404, "页面不存在");
        verifyNoInteractions(content);
    }

    // ---------------------------------------------------------------- 503 / 500 / 日志

    @Test
    void unavailableIs503(CapturedOutput output) throws Exception {
        doThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用")).when(content).listVideos(any());
        MvcResult r = call(raw(SEARCH + "?wd=" + enc(SECRET_KEYWORD))
                .header("Cookie", "user_check=" + LEAK));
        assertErrorPage(r, 503, "服务暂时不可用");
        assertThat(body(r)).doesNotContain(SECRET_KEYWORD);
        assertThat(output.getAll()).contains("site page unavailable").doesNotContain(LEAK).doesNotContain(SECRET_KEYWORD)
                .doesNotContain(enc(SECRET_KEYWORD));
    }

    @Test
    void categoryReadFailureIs503() throws Exception {
        when(content.listCategories()).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        assertErrorPage(call(SEARCH + "?area=x"), 503, "服务暂时不可用");
    }

    @Test
    void laterFailureIsComplete500WithoutPartialDataOrKeywordInLogs(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("jdbc:mysql://" + LEAK + "/db " + SECRET_KEYWORD,
                new SQLException("select vod_pwd from mac_vod where vod_name like '%" + SECRET_KEYWORD + "%'")))
                .when(content).listVideos(any());
        MvcResult r = call(raw(SEARCH + "?wd=" + enc(SECRET_KEYWORD))
                .header("Authorization", "Basic " + LEAK));
        assertErrorPage(r, 500, "页面暂时无法显示");
        assertThat(body(r)).doesNotContain(SECRET_KEYWORD).doesNotContain("电影").doesNotContain("Exception");
        assertThat(output.getAll()).contains("site page failed: java.lang.IllegalStateException")
                .doesNotContain(LEAK).doesNotContain(SECRET_KEYWORD).doesNotContain(enc(SECRET_KEYWORD))
                .doesNotContain("mac_vod");
    }

    @Test
    void successfulSearchDoesNotLogKeywordOrQuery(CapturedOutput output) throws Exception {
        ok(call(SEARCH + "?wd=" + enc(SECRET_KEYWORD)));
        searchPage(call(SEARCH + "?wd=" + enc(SECRET_KEYWORD) + "&foo=1"), 400);
        assertThat(output.getAll()).doesNotContain(SECRET_KEYWORD).doesNotContain(enc(SECRET_KEYWORD));
    }

    @Test
    void threePagesStillRejectSearchPathAndQueries() throws Exception {
        // 三页的路由不因搜索页而改变：分类页仍拒绝查询串
        assertErrorPage(call("/index.php/vod/type/id/1.html?wd=x"), 404, "页面不存在");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
