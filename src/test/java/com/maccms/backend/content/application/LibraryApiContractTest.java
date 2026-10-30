package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.api.ActorController;
import com.maccms.backend.content.api.ArticleController;
import com.maccms.backend.content.api.LinkController;
import com.maccms.backend.content.api.RoleController;
import com.maccms.backend.content.api.TopicController;
import com.maccms.backend.content.api.VideoController;
import com.maccms.backend.content.api.WebsiteController;
import com.maccms.backend.content.infrastructure.ActorMapper;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.LinkMapper;
import com.maccms.backend.content.infrastructure.RoleMapper;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.TopicMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodPlotMapper;
import com.maccms.backend.content.infrastructure.WebsiteMapper;
import com.maccms.backend.contract.OpenApiContract;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 契约 1.2.0 的 HTTP 契约测试：真实的 Controller + Service + 异常处理，Mapper 用 Mockito 替身。每个响应按
 * docs/contracts/content-read.openapi.yaml 严格校验（字段集合完全一致、不出现 null）。SQL 本身由 LibraryMySqlIT 在真实 MySQL 上验证，
 * 与旧 PHP 的 ID 序列对照见 LibraryMySqlIT 与 docs/architecture/11-content-read-completion.md。
 */
@WebMvcTest(controllers = {ArticleController.class, TopicController.class, ActorController.class, RoleController.class,
        WebsiteController.class, LinkController.class, VideoController.class})
@Import({ContentReadService.class, ContentCatalogs.class, ArticleReadService.class, TopicReadService.class,
        ActorReadService.class, RoleReadService.class, WebsiteReadService.class, LinkReadService.class,
        VideoPlotReadService.class})
@EnableConfigurationProperties(ContentProperties.class)
@ExtendWith(OutputCaptureExtension.class)
class LibraryApiContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LEAK = "SYNTHETIC-LEAK-MARKER";
    private static OpenApiContract contract;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TypeMapper typeMapper;
    @MockitoBean
    GroupMapper groupMapper;
    @MockitoBean
    VodMapper vodMapper;
    @MockitoBean
    VodPlotMapper vodPlotMapper;
    @MockitoBean
    ArtMapper artMapper;
    @MockitoBean
    TopicMapper topicMapper;
    @MockitoBean
    ActorMapper actorMapper;
    @MockitoBean
    RoleMapper roleMapper;
    @MockitoBean
    WebsiteMapper websiteMapper;
    @MockitoBean
    LinkMapper linkMapper;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
    }

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(typeMapper.selectEnabledTypes(2)).thenReturn(LibraryFixtures.articleTypes());
        when(typeMapper.selectEnabledTypes(8)).thenReturn(LibraryFixtures.actorTypes());
        when(typeMapper.selectEnabledTypes(11)).thenReturn(LibraryFixtures.websiteTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(LibraryFixtures.guestGroup());
    }

    /** 请求并按契约校验；path 为契约中的路径模板。 */
    private JsonNode call(String url, String path, int status) throws Exception {
        // URI.create：查询串按原样发送（不经 URI 模板再编码一次），测试中的 %xx 就是请求中的百分号编码
        MvcResult r = mvc.perform(get(URI.create(url))).andReturn();
        assertThat(r.getResponse().getStatus()).as("HTTP 状态 " + url).isEqualTo(status);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        contract.assertMatches(body, contract.responseSchema(path, "get", status));
        return body;
    }

    private JsonNode error(String url, String path, int status, String code) throws Exception {
        JsonNode body = call(url, path, status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        return body;
    }

    private static List<Integer> ids(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false).map(n -> n.get("id").asInt()).toList();
    }

    // ------------------------------------------------------------------ 分类

    @Test
    void articleCategoriesUseTypeMid2AndSameVisibilityRules() throws Exception {
        JsonNode body = call("/api/v1/article-categories", "/api/v1/article-categories", 200);
        // 23 游客无列表位 → 不可见；24 有列表位（没有详情位）→ 出现在分类树中
        assertThat(ids(body.get("items"))).containsExactly(21, 24);
        assertThat(ids(body.get("items").get(0).get("children"))).containsExactly(22);
    }

    @Test
    void actorAndWebsiteCategoriesUseTheirOwnTypeMid() throws Exception {
        assertThat(ids(call("/api/v1/actor-categories", "/api/v1/actor-categories", 200).get("items")))
                .containsExactly(31);
        assertThat(ids(call("/api/v1/website-categories", "/api/v1/website-categories", 200).get("items")))
                .containsExactly(41);
    }

    // ------------------------------------------------------------------ 文章

    @Test
    void articleListDefaultsAndVisibleCategories() throws Exception {
        when(artMapper.countPublished(any(), any())).thenReturn(2L);
        when(artMapper.selectPublishedPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(LibraryFixtures.artSummary(2, 22), LibraryFixtures.artSummary(1, 21)));
        JsonNode body = call("/api/v1/articles", "/api/v1/articles", 200);
        assertThat(body.get("pageSize").asInt()).isEqualTo(20);
        assertThat(body.get("total").asLong()).isEqualTo(2);
        assertThat(body.get("items").get(0).get("category").get("id").asInt()).isEqualTo(22);
        assertThat(body.get("items").get(0).get("detailPasswordRequired").asBoolean()).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(artMapper).selectPublishedPage(types.capture(), criteria.capture(), eq(SortColumn.TIME), eq(true),
                eq(0L), eq(20));
        assertThat(types.getValue()).containsExactlyInAnyOrder(21, 22, 24);
        assertThat(criteria.getValue()).isEqualTo(ContentCriteria.NONE);
    }

    @Test
    void articleListTranslatesCategorySortPagingAndFilters() throws Exception {
        when(artMapper.countPublished(any(), any())).thenReturn(30L);
        call("/api/v1/articles?categoryId=21&sort=hits_week&order=asc&page=2&pageSize=10&level=9&letter=0-9",
                "/api/v1/articles", 200);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(artMapper).selectPublishedPage(types.capture(), criteria.capture(), eq(SortColumn.HITS_WEEK), eq(false),
                eq(10L), eq(10));
        assertThat(types.getValue()).containsExactlyInAnyOrder(21, 22);
        assertThat(criteria.getValue().level()).isEqualTo(9);
        assertThat(criteria.getValue().letters()).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void parametersOfOtherTypesAndLegacyNamesAreIgnored() throws Exception {
        when(artMapper.countPublished(any(), any())).thenReturn(0L);
        call("/api/v1/articles?area=%E5%A4%A7%E9%99%86&videoId=1&type=pic&wd=x&tid=3&by=hits&sex=%E7%94%B7", "/api/v1/articles", 200);
        verify(artMapper).countPublished(any(), eq(ContentCriteria.NONE));
        verify(artMapper, never()).selectPublishedPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/articles?sort=referer, sort",
            "/api/v1/articles?sort=rnd, sort",
            "/api/v1/articles?order=up, order",
            "/api/v1/articles?page=0, page",
            "/api/v1/articles?pageSize=101, pageSize",
            "/api/v1/articles?categoryId=0, categoryId",
            "/api/v1/articles?level=0, level",
            "/api/v1/articles?level=all, level",
            "/api/v1/articles?level=1&level=2, level",
            "/api/v1/articles?letter=AB, letter",
            "/api/v1/articles?letter=%E4%B8%AD, letter",
            "/api/v1/articles?letter=1-9, letter",
            "/api/v1/topics?letter=-, letter",
            "/api/v1/actors?sex=%E7%94%B7%E5%A5%B3, sex",
            "/api/v1/actors?area=a%2Cb, area",
            "/api/v1/actors?area=a&area=b, area",
            "/api/v1/actors?starSign=%20x, starSign",
            "/api/v1/actors?blood=%01, blood",
            "/api/v1/websites?lang=12345678901, lang",
            "/api/v1/websites?sort=sort, sort",
            "/api/v1/roles?videoId=0, videoId",
            "/api/v1/roles?videoId=4294967296, videoId",
            "/api/v1/links?type=text, type",
            "/api/v1/links?sort=time, sort"})
    void invalidListParametersReturn400(String url, String field) throws Exception {
        String path = url.substring(0, url.indexOf('?'));
        JsonNode body = error(url, path, 400, "INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
    }

    @Test
    void validationOrderIsCategorySortOrderPagePageSizeThenFilters() throws Exception {
        JsonNode body = error("/api/v1/articles?letter=AB&pageSize=0&categoryId=99999", "/api/v1/articles", 400,
                "INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("categoryId");
        body = error("/api/v1/articles?letter=AB&pageSize=0", "/api/v1/articles", 400, "INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("pageSize");
    }

    @ParameterizedTest
    @ValueSource(strings = {"23", "999"})
    void hiddenOrMissingCategoryReturns404(String categoryId) throws Exception {
        error("/api/v1/articles?categoryId=" + categoryId, "/api/v1/articles", 404, "CATEGORY_NOT_FOUND");
        verify(artMapper, never()).countPublished(any(), any());
    }

    @Test
    void noVisibleCategoryMeansEmptyListWithoutQuery() throws Exception {
        when(typeMapper.selectEnabledTypes(8)).thenReturn(List.of());
        JsonNode body = call("/api/v1/actors", "/api/v1/actors", 200);
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("total").asLong()).isZero();
        verifyNoInteractions(actorMapper);
    }

    @Test
    void articleDetailDecodesPagesAndScreenshots() throws Exception {
        when(artMapper.selectPublishedDetail(5)).thenReturn(LibraryFixtures.artDetail(5, 22, 0, 0));
        JsonNode body = call("/api/v1/articles/5", "/api/v1/articles/{id}", 200);
        assertThat(body.get("pages")).hasSize(2);
        assertThat(body.get("pages").get(0).get("title").asString()).isEqualTo("第一页");
        assertThat(body.get("pages").get(0).get("note").asString()).isEqualTo("注1");
        assertThat(body.get("pages").get(1).get("note").asString()).isEmpty();
        assertThat(body.get("pages").get(1).get("content").asString()).isEqualTo("<p>二</p>");
        assertThat(body.get("screenshots").get(1).get("name").asString()).isEqualTo("2");
        assertThat(body.get("screenshots").get(1).get("url").asString()).isEqualTo("upload/s2.jpg");
        assertThat(body.get("scoreAll").asInt()).isEqualTo(22);
    }

    @Test
    void articleDetailVisibilityOrder() throws Exception {
        error("/api/v1/articles/1", "/api/v1/articles/{id}", 404, "ARTICLE_NOT_FOUND");
        when(artMapper.selectPublishedDetail(2)).thenReturn(LibraryFixtures.artDetail(2, 23, 0, 0));
        error("/api/v1/articles/2", "/api/v1/articles/{id}", 404, "ARTICLE_NOT_FOUND");
        // 24：分类可见但游客没有详情位
        when(artMapper.selectPublishedDetail(3)).thenReturn(LibraryFixtures.artDetail(3, 24, 0, 0));
        error("/api/v1/articles/3", "/api/v1/articles/{id}", 404, "ARTICLE_NOT_FOUND");
        // 积分先于密码（旧版 label_art_detail 的权限检查在 Art::detail 的密码检查之前）
        when(artMapper.selectPublishedDetail(4)).thenReturn(LibraryFixtures.artDetail(4, 21, 1, 1));
        error("/api/v1/articles/4", "/api/v1/articles/{id}", 403, "ARTICLE_POINTS_REQUIRED");
        when(artMapper.selectPublishedDetail(5)).thenReturn(LibraryFixtures.artDetail(5, 21, 1, 0));
        error("/api/v1/articles/5", "/api/v1/articles/{id}", 403, "ARTICLE_PASSWORD_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "4294967296", "00000000001"})
    void invalidIdsReturn400(String id) throws Exception {
        for (String base : List.of("/api/v1/articles/", "/api/v1/topics/", "/api/v1/actors/", "/api/v1/roles/",
                "/api/v1/websites/", "/api/v1/videos/")) {
            String path = base.equals("/api/v1/videos/") ? "/api/v1/videos/{id}/plots" : base + "{id}";
            String url = base.equals("/api/v1/videos/") ? base + id + "/plots" : base + id;
            JsonNode body = error(url, path, 400, "INVALID_PARAMETER");
            assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("id");
        }
    }

    // ------------------------------------------------------------------ 专题

    @Test
    void topicListHasNoCategoryAndUsesFilters() throws Exception {
        when(topicMapper.countPublished(any())).thenReturn(1L);
        when(topicMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(LibraryFixtures.topicSummary(7)));
        JsonNode body = call("/api/v1/topics?categoryId=1&level=2&letter=z&sort=up", "/api/v1/topics", 200);
        assertThat(ids(body.get("items"))).containsExactly(7);
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(topicMapper).selectPublishedPage(criteria.capture(), eq(SortColumn.UP), eq(true), eq(0L), eq(20));
        assertThat(criteria.getValue().level()).isEqualTo(2);
        assertThat(criteria.getValue().letters()).containsExactly("z");
    }

    @Test
    void topicDetailJoinsRelatedIdsAndSkipsTagMatchingWhileSearchIsDisabled() throws Exception {
        when(topicMapper.selectPublishedDetail(7)).thenReturn(LibraryFixtures.topicDetail(7, "标签A", " 3,abc,5x,0,3", "9"));
        when(vodMapper.selectPublishedByIds(any(), any())).thenReturn(List.of(Fixtures.summary(5, 6), Fixtures.summary(3, 1)));
        when(artMapper.selectPublishedByIds(any(), any())).thenReturn(List.of(LibraryFixtures.artSummary(9, 21)));
        JsonNode body = call("/api/v1/topics/7", "/api/v1/topics/{id}", 200);
        assertThat(ids(body.get("relatedVideos"))).containsExactly(5, 3);
        assertThat(ids(body.get("relatedArticles"))).containsExactly(9);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> vodIds = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> vodTypes = ArgumentCaptor.forClass(Collection.class);
        verify(vodMapper).selectPublishedByIds(vodTypes.capture(), vodIds.capture());
        assertThat(vodIds.getValue()).containsExactly(3L, 5L);
        assertThat(vodTypes.getValue()).containsExactlyInAnyOrder(1, 6, 7, 2, 13);
        verify(vodMapper, never()).selectPublishedByTagPatterns(any(), any());
        verify(artMapper, never()).selectPublishedByTagPatterns(any(), any());
    }

    @Test
    void topicNotFound() throws Exception {
        error("/api/v1/topics/1", "/api/v1/topics/{id}", 404, "TOPIC_NOT_FOUND");
    }

    // ------------------------------------------------------------------ 演员、网址

    @Test
    void actorListPassesFacetFilters() throws Exception {
        when(actorMapper.countPublished(any(), any())).thenReturn(1L);
        when(actorMapper.selectPublishedPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(LibraryFixtures.actorSummary(1, 31)));
        JsonNode body = call("/api/v1/actors?sex=%E5%A5%B3&area=%E5%A4%A7%E9%99%86&starSign=%E7%99%BD%E7%BE%8A%E5%BA%A7"
                + "&blood=O&lang=x", "/api/v1/actors", 200);
        assertThat(body.get("items").get(0).get("starSign").asString()).isEqualTo("白羊座");
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(actorMapper).countPublished(any(), criteria.capture());
        assertThat(criteria.getValue()).isEqualTo(new ContentCriteria(null, null, "女", "大陆", null, "白羊座", "O",
                null, null));
    }

    @Test
    void actorAndWebsiteDetails() throws Exception {
        when(actorMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.actorDetail(1, 31));
        assertThat(call("/api/v1/actors/1", "/api/v1/actors/{id}", 200).get("content").asString())
                .isEqualTo("<p>演员</p>");
        when(actorMapper.selectPublishedDetail(2)).thenReturn(LibraryFixtures.actorDetail(2, 99));
        error("/api/v1/actors/2", "/api/v1/actors/{id}", 404, "ACTOR_NOT_FOUND");

        when(websiteMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.websiteDetail(1, 41));
        JsonNode web = call("/api/v1/websites/1", "/api/v1/websites/{id}", 200);
        assertThat(web.get("url").asString()).isEqualTo("https://example.test/1");
        assertThat(web.get("screenshots").get(0).get("name").asString()).isEqualTo("首页");
        error("/api/v1/websites/2", "/api/v1/websites/{id}", 404, "WEBSITE_NOT_FOUND");
    }

    @Test
    void websiteListAcceptsRefererSortsAndAreaLang() throws Exception {
        when(websiteMapper.countPublished(any(), any())).thenReturn(1L);
        when(websiteMapper.selectPublishedPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(LibraryFixtures.websiteSummary(1, 41)));
        call("/api/v1/websites?sort=referer_month&area=%E5%A4%A7%E9%99%86&lang=%E5%9B%BD%E8%AF%AD", "/api/v1/websites",
                200);
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(websiteMapper).selectPublishedPage(eq(Set.of(41)), criteria.capture(), eq(SortColumn.REFERER_MONTH),
                eq(true), eq(0L), eq(20));
        assertThat(criteria.getValue().area()).isEqualTo("大陆");
        assertThat(criteria.getValue().lang()).isEqualTo("国语");
    }

    // ------------------------------------------------------------------ 角色

    @Test
    void roleListAttachesOnlyVisibleVideos() throws Exception {
        when(roleMapper.countPublished(any())).thenReturn(3L);
        when(roleMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt())).thenReturn(List.of(
                LibraryFixtures.roleSummary(1, 10), LibraryFixtures.roleSummary(2, 11), LibraryFixtures.roleSummary(3, 0)));
        // 10 在可见分类 6；11 在不可见分类 3；角色 3 没有所属视频
        when(vodMapper.selectPublishedRefs(any())).thenReturn(List.of(LibraryFixtures.vodRef(10, 6),
                LibraryFixtures.vodRef(11, 3)));
        JsonNode body = call("/api/v1/roles?videoId=10", "/api/v1/roles", 200);
        assertThat(body.get("items").get(0).get("video").get("id").asLong()).isEqualTo(10);
        assertThat(body.get("items").get(0).get("video").get("categoryId").asInt()).isEqualTo(6);
        assertThat(body.get("items").get(1).get("video").get("id").asLong()).isZero();
        assertThat(body.get("items").get(1).get("video").get("name").asString()).isEmpty();
        assertThat(body.get("items").get(2).get("video").get("id").asLong()).isZero();

        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(roleMapper).countPublished(criteria.capture());
        assertThat(criteria.getValue().videoId()).isEqualTo(10L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> refIds = ArgumentCaptor.forClass(Collection.class);
        verify(vodMapper).selectPublishedRefs(refIds.capture());
        assertThat(refIds.getValue()).containsExactly(10L, 11L);
    }

    @Test
    void roleDetail() throws Exception {
        when(roleMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.roleDetail(1, 10));
        when(vodMapper.selectPublishedRefs(any())).thenReturn(List.of(LibraryFixtures.vodRef(10, 1)));
        assertThat(call("/api/v1/roles/1", "/api/v1/roles/{id}", 200).get("video").get("name").asString())
                .isEqualTo("视频10");
        error("/api/v1/roles/2", "/api/v1/roles/{id}", 404, "ROLE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ 友链

    @Test
    void linkListDefaultsToIdAscending() throws Exception {
        when(linkMapper.count(any())).thenReturn(2L);
        when(linkMapper.selectPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(LibraryFixtures.link(1, 0), LibraryFixtures.link(2, 1)));
        JsonNode body = call("/api/v1/links", "/api/v1/links", 200);
        assertThat(ids(body.get("items"))).containsExactly(1, 2);
        verify(linkMapper).selectPage(eq(ContentCriteria.NONE), eq(SortColumn.ID), eq(false), eq(0L), eq(20));

        call("/api/v1/links?type=pic&sort=sort&order=desc", "/api/v1/links", 200);
        ArgumentCaptor<ContentCriteria> criteria = ArgumentCaptor.forClass(ContentCriteria.class);
        verify(linkMapper).selectPage(criteria.capture(), eq(SortColumn.SORT), eq(true), eq(0L), eq(20));
        assertThat(criteria.getValue().linkType()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 分集剧情

    @Test
    void plotsFollowVideoDetailVisibility() throws Exception {
        when(vodPlotMapper.selectPublishedPlot(1)).thenReturn(LibraryFixtures.plot(1, 6, 0, 0));
        JsonNode body = call("/api/v1/videos/1/plots", "/api/v1/videos/{id}/plots", 200);
        assertThat(body.get("videoId").asLong()).isEqualTo(1);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(1).get("detail").asString()).isEmpty();

        error("/api/v1/videos/2/plots", "/api/v1/videos/{id}/plots", 404, "VIDEO_NOT_FOUND");
        when(vodPlotMapper.selectPublishedPlot(3)).thenReturn(LibraryFixtures.plot(3, 7, 0, 0)); // 7 没有详情位
        error("/api/v1/videos/3/plots", "/api/v1/videos/{id}/plots", 404, "VIDEO_NOT_FOUND");
        when(vodPlotMapper.selectPublishedPlot(4)).thenReturn(LibraryFixtures.plot(4, 1, 1, 1));
        error("/api/v1/videos/4/plots", "/api/v1/videos/{id}/plots", 403, "VIDEO_PASSWORD_REQUIRED");
        when(vodPlotMapper.selectPublishedPlot(5)).thenReturn(LibraryFixtures.plot(5, 1, 0, 1));
        error("/api/v1/videos/5/plots", "/api/v1/videos/{id}/plots", 403, "VIDEO_COPYRIGHT_RESTRICTED");
    }

    // ------------------------------------------------------------------ 故障

    @ParameterizedTest
    @CsvSource({"/api/v1/article-categories, /api/v1/article-categories", "/api/v1/articles, /api/v1/articles",
            "/api/v1/articles/1, /api/v1/articles/{id}", "/api/v1/topics/1, /api/v1/topics/{id}",
            "/api/v1/actors, /api/v1/actors", "/api/v1/roles, /api/v1/roles", "/api/v1/websites/1, /api/v1/websites/{id}",
            "/api/v1/links, /api/v1/links", "/api/v1/videos/1/plots, /api/v1/videos/{id}/plots"})
    void connectionFailureReturnsExact503Body(String url, String path, CapturedOutput output) throws Exception {
        RuntimeException e = new org.mybatis.spring.MyBatisSystemException(LEAK,
                new org.apache.ibatis.exceptions.PersistenceException(LEAK,
                        new org.springframework.jdbc.CannotGetJdbcConnectionException("jdbc:mysql://" + LEAK + "-host/db",
                                new java.sql.SQLTransientConnectionException(LEAK))));
        when(typeMapper.selectEnabledTypes(anyInt())).thenThrow(e);
        when(typeMapper.selectEnabledVodTypes()).thenThrow(e);
        when(groupMapper.selectGroupPermission(anyInt())).thenThrow(e);
        when(topicMapper.selectPublishedDetail(anyLong())).thenThrow(e);
        when(roleMapper.countPublished(any())).thenThrow(e);
        when(linkMapper.count(any())).thenThrow(e);
        when(artMapper.selectPublishedDetail(anyLong())).thenThrow(e);
        when(websiteMapper.selectPublishedDetail(anyLong())).thenThrow(e);
        when(vodPlotMapper.selectPublishedPlot(anyLong())).thenThrow(e);
        MvcResult r = mvc.perform(get(URI.create(url))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂时不可用\",\"fieldErrors\":[]}");
        contract.assertMatches(JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                contract.responseSchema(path, "get", 503));
        assertThat(output.getAll()).contains("database unavailable").doesNotContain(LEAK);
    }

    @Test
    void statementTimeoutOnListIsA503() throws Exception {
        java.sql.SQLException timeout = new java.sql.SQLException("synthetic", "HY000", 3024);
        when(artMapper.countPublished(any(), any())).thenThrow(
                new org.springframework.jdbc.UncategorizedSQLException("synthetic", "SELECT 1", timeout));
        error("/api/v1/articles", "/api/v1/articles", 503, "SERVICE_UNAVAILABLE");
    }
}
