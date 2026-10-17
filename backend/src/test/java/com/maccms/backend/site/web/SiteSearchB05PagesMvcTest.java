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
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.net.URLEncoder;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B05（MACC-29）SSR 搜索页承接 B04 新键、tid 与 by=rnd 的 MockMvc 测试（content 为替身，不连数据库）：交给 content 的只读调用参数、
 * 隐藏字段与链接键序、筛选区（契约稿，15 文档第 3.3 节）、随机排序不显示分页、开关关闭时包含匹配新键在查库前 400。
 * 真实 MySQL 与旧版对照见 VideoTagGoldenMySqlIT、SiteSearchMySqlIT。
 */
@WebMvcTest(controllers = {SiteSearchController.class, SiteContentController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteSearchB05PagesMvcTest {

    static final String SEARCH = "/index.php/vod/search.html";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.searchEnabled()).thenReturn(true);
        CategoryExtendDto movie = new CategoryExtendDto("动作", "大陆", "国语", "2026", "演员甲,演员乙", "导演甲",
                "正片,预告片,0", "高清版, 超清版");
        CategoryExtendDto tv = new CategoryExtendDto("", "", "", "", "演员乙", "", "正片", "");
        when(content.listCategories()).thenReturn(new CategoryListResponse(List.of(
                new CategoryDto(1, "电影", "en1", 1, 0, "", "", "", "", "", movie, List.of(
                        new CategoryDto(6, "动作片", "en6", 6, 1, "", "", "", "", "", movie, List.of()))),
                new CategoryDto(2, "连续剧", "en2", 2, 0, "", "", "", "", "", tv, List.of()))));
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            // 随机排序：算法一以随机页覆盖请求页（这里固定返回第 2 页），total 45
            int p = q.random() ? 2 : q.page();
            return page(summaries(p * 100L, p > 3 ? 0 : 20, ref(6, "动作片", 1)), p, q.pageSize(), 45);
        });
    }

    private Document ok(String pathAndQuery) throws Exception {
        MvcResult r = call(pathAndQuery);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult call(String pathAndQuery) throws Exception {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(path))
                .with(req -> {
                    req.setQueryString(query);
                    return req;
                })).andReturn();
    }

    private VideoListQuery lastQuery() {
        ArgumentCaptor<VideoListQuery> c = ArgumentCaptor.forClass(VideoListQuery.class);
        verify(content).listVideos(c.capture());
        return c.getValue();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static List<String> chips(Document doc, String key) {
        return doc.select("ul[aria-labelledby=search-filter-" + key + "] a").eachText();
    }

    @Test
    void newKeysAndTidAreOneReadOnlyQueryWithInternalTidCondition() throws Exception {
        Document doc = ok(SEARCH + "?tid=06&actor=" + enc("演员甲") + "&letter=A&state=" + enc("正片") + "&ids=1,,2"
                + "&name=" + enc("片") + "&tag=" + enc("标签") + "&version=" + enc("高清版") + "&director=" + enc("导演甲"));
        VideoListQuery q = lastQuery();
        assertThat(q.categoryId()).as("tid 不映射为 categoryId（Q2）").isNull();
        assertThat(q.legacy()).isEqualTo(VideoLegacyConditions.builder().tid(6L).build());
        assertThat(q.filter()).isEqualTo(new VideoFilter(null, null, null, null, null, null, "A", "正片", "高清版", "演员甲",
                "导演甲", "标签", "片", "1,,2"));
        assertThat(q.random()).isFalse();
        assertThat(q.pageSize()).isEqualTo(20);
        // canonical：键按 mac_url 排序、tid 为 intval 后的值
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test/index.php/"
                + "vod/search.html?actor=" + enc("演员甲") + "&director=" + enc("导演甲") + "&ids=1%2C%2C2&letter=A&name="
                + enc("片") + "&state=" + enc("正片") + "&tag=" + enc("标签") + "&tid=6&version=" + enc("高清版"));
        // 隐藏字段：除 wd 外的全部条件，键序与链接相同
        assertThat(doc.select("form.search-form input[type=hidden]").eachAttr("name"))
                .containsExactly("actor", "director", "ids", "letter", "name", "state", "tag", "tid", "version");
        assertThat(doc.selectFirst("form.search-form input[name=ids]").attr("value")).isEqualTo("1,,2");
        assertThat(doc.selectFirst("h1").text()).isEqualTo("筛选结果");
    }

    @Test
    void filterAreaFollowsTheProposedContract() throws Exception {
        Document doc = ok(SEARCH + "?letter=B&tid=6&tag=" + enc("标签甲"));
        assertThat(doc.select(".filter-group__label").eachText()).containsExactly("地区", "语言", "年份", "首字母", "状态",
                "版本", "类型", "主演", "导演", "标签", "分类", "排序");
        List<String> letters = chips(doc, "letter");
        assertThat(letters).hasSize(28).startsWith("全部", "A", "B").endsWith("Z", "0-9");
        assertThat(doc.selectFirst("ul[aria-labelledby=search-filter-letter] a[aria-current=true]").text()).isEqualTo("B");
        // 候选值来自分类扩展字段，跳过 '0'、首尾空白等不能回读的值，跨分类去重
        assertThat(chips(doc, "state")).containsExactly("全部", "正片", "预告片");
        assertThat(chips(doc, "version")).containsExactly("全部", "高清版");
        assertThat(chips(doc, "actor")).containsExactly("全部", "演员甲", "演员乙");
        assertThat(chips(doc, "director")).containsExactly("全部", "导演甲");
        // 没有候选值的条件：全部 + 当前值；tid 显示可见分类名
        assertThat(chips(doc, "tag")).containsExactly("全部", "标签甲");
        assertThat(chips(doc, "tid")).containsExactly("全部", "动作片");
        Element removeTid = doc.selectFirst("ul[aria-labelledby=search-filter-tid] a");
        assertThat(removeTid.attr("href")).isEqualTo(SEARCH + "?letter=B&tag=" + enc("标签甲"));
        assertThat(doc.select("ul[aria-labelledby=search-filter-name], ul[aria-labelledby=search-filter-ids]")).isEmpty();
        // 筛选链接回到第 1 页，保留其余条件
        Element pickA = doc.select("ul[aria-labelledby=search-filter-letter] a").get(1);
        assertThat(pickA.attr("href")).isEqualTo(SEARCH + "?letter=A&tag=" + enc("标签甲") + "&tid=6");
    }

    @Test
    void containsMatchGroupsDisappearAndNewContainsKeysAre400WhenSearchIsOff() throws Exception {
        when(content.searchEnabled()).thenReturn(false);
        Document doc = ok(SEARCH + "?letter=A");
        assertThat(doc.select(".filter-group__label").eachText()).doesNotContain("类型", "主演", "导演")
                .contains("首字母", "状态", "版本");
        for (String q : List.of("actor=x", "director=x", "tag=x", "name=x", "letter=A&actor=x")) {
            MvcResult r = call(SEARCH + "?" + q);
            assertThat(r.getResponse().getStatus()).as(q).isEqualTo(400);
        }
        verify(content).listVideos(any());
    }

    @Test
    void randomSortHasNoPaginationAndShowsCurrentRandomChip() throws Exception {
        Document doc = ok(SEARCH + "?by=rnd&letter=A&page=5");
        VideoListQuery q = lastQuery();
        assertThat(q.random()).isTrue();
        assertThat(q.sort()).isEqualTo(VideoSort.HITS_WEEK);
        assertThat(q.descending()).isTrue();
        assertThat(q.page()).as("请求的页码交给 content（算法二使用）").isEqualTo(5);
        assertThat(doc.select("nav.pagination")).isEmpty();
        assertThat(doc.selectFirst(".search-summary").text()).isEqualTo("共 45 条");
        assertThat(chips(doc, "by")).containsExactly("时间", "人气", "评分", "随机");
        assertThat(doc.selectFirst("ul[aria-labelledby=search-filter-by] a[aria-current=true]").text()).isEqualTo("随机");
        assertThat(doc.selectFirst("ul[aria-labelledby=search-filter-by] a").attr("href"))
                .as("换成时间排序时去掉 rnd").isEqualTo(SEARCH + "?by=time&letter=A");
        assertThat(doc.selectFirst("form.search-form input[name=by]").attr("value")).isEqualTo("rnd");
    }

    @Test
    void randomSortBeyondTheResultsIsStill200() throws Exception {
        when(content.listVideos(any())).thenAnswer(inv -> page(List.of(), 7, 20, 0));
        Document doc = ok(SEARCH + "?by=rnd&page=7");
        assertThat(doc.select(".search-empty")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?tid=0", "?tid=abc", "?tid=", "?level=0", "?level=00", "?year=0000", "?year=0000-0000",
        "?letter=0", "?ids=+0+"})
    void zeroLikeValuesMeanNoConditionAndNoQuery(String q) throws Exception {
        Document doc = ok(SEARCH + q);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("搜索视频");
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/vod/search.html");
        verifyNoInteractions(content);
    }

    @Test
    void negativeTidStillFiltersAndShowsTheNumber() throws Exception {
        Document doc = ok(SEARCH + "?tid=-1");
        assertThat(lastQuery().legacy().tid()).isEqualTo(-1L);
        assertThat(chips(doc, "tid")).containsExactly("全部", "分类 -1");
    }

    @Test
    void overBudgetCombinationIs400WithoutEcho() throws Exception {
        String han = enc("汉".repeat(64));
        MvcResult r = call(SEARCH + "?wd=" + han + "&actor=" + han + "&tag=" + han);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("汉汉");
        verifyNoInteractions(content);
    }
}
