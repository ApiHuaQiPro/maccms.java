package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.content.api.VideoCategoryController;
import com.maccms.backend.content.api.VideoController;
import com.maccms.backend.content.application.ContentProperties;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodFilterCriteria;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP 契约测试：真实的 Controller + Service + 异常处理，Mapper 用 Mockito 替身。
 * 每个响应都按 docs/contracts/content-read.openapi.yaml 严格校验（字段集合必须完全一致）。
 * SQL 本身（状态过滤、排序、LIMIT）由 ContentApiMySqlIT 在真实 MySQL 上验证。
 */
@WebMvcTest(controllers = {VideoController.class, VideoCategoryController.class})
@Import(ContentReadService.class)
@EnableConfigurationProperties(ContentProperties.class)
@ExtendWith(OutputCaptureExtension.class)
class ContentApiContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static OpenApiContract contract;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TypeMapper typeMapper;
    @MockitoBean
    GroupMapper groupMapper;
    @MockitoBean
    VodMapper vodMapper;
    /** VideoController 的分集剧情端点（契约 1.2.0）由 LibraryApiContractTest 覆盖，这里只需占位。 */
    @MockitoBean
    VideoPlotReadService videoPlotReadService;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
    }

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(Fixtures.guestGroup());
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String path, String method, int status) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as("HTTP 状态").isEqualTo(status);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        if (path != null) {
            contract.assertMatches(body, contract.responseSchema(path, method, status));
        } else {
            contract.assertMatches(body, contract.schema("ErrorResponse"));
        }
        return body;
    }

    // ---------- 分类 ----------

    @Test
    void categoriesFormTreeOfVisibleCategoriesInSortOrder() throws Exception {
        JsonNode body = call(get("/api/v1/video-categories"), "/api/v1/video-categories", "get", 200);
        JsonNode items = body.get("items");
        // 3 没有游客列表权限位 1 → 不可见
        assertThat(items).extracting(n -> n.get("id").asInt()).containsExactly(1, 2);
        assertThat(items.get(0).get("children")).extracting(n -> n.get("id").asInt()).containsExactly(6, 7);
        assertThat(items.get(1).get("children")).extracting(n -> n.get("id").asInt()).containsExactly(13);
        assertThat(items.get(0).get("extend").get("classes").asString()).isEqualTo("喜剧,爱情");
        assertThat(items.get(0).get("children").get(0).get("children")).isEmpty();
    }

    @Test
    void categoriesEmptyWhenGuestGroupMissing() throws Exception {
        when(groupMapper.selectGroupPermission(1)).thenReturn(null);
        JsonNode body = call(get("/api/v1/video-categories"), "/api/v1/video-categories", "get", 200);
        assertThat(body.get("items")).isEmpty();
    }

    // ---------- 列表 ----------

    @Test
    void listDefaultsAndPassesVisibleCategoriesToSql() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(2L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(2, 6), Fixtures.summary(1, 1)));

        JsonNode body = call(get("/api/v1/videos"), "/api/v1/videos", "get", 200);

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("pageSize").asInt()).isEqualTo(20);
        assertThat(body.get("total").asLong()).isEqualTo(2);
        assertThat(body.get("totalPages").asLong()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("category").get("id").asInt()).isEqualTo(6);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(vodMapper).selectPublishedPage(ids.capture(), eq(VideoSort.TIME), eq(true), eq(0L), eq(20));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1, 6, 7, 2, 13);
    }

    @Test
    void listByParentCategoryIncludesDirectChildren() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(0L);
        call(get("/api/v1/videos").param("categoryId", "1"), "/api/v1/videos", "get", 200);
        verify(vodMapper).countPublished(Set.of(1, 6, 7));
    }

    @Test
    void listSortOrderAndPagingAreTranslated() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(500L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt())).thenReturn(List.of());
        JsonNode body = call(get("/api/v1/videos").param("sort", "hits_week").param("order", "asc")
                .param("page", "3").param("pageSize", "100"), "/api/v1/videos", "get", 200);
        verify(vodMapper).selectPublishedPage(any(), eq(VideoSort.HITS_WEEK), eq(false), eq(200L), eq(100));
        assertThat(body.get("totalPages").asLong()).isEqualTo(5);
    }

    @Test
    void pageBeyondTotalReturnsEmptyItemsWithoutRowQuery() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(3L);
        JsonNode body = call(get("/api/v1/videos").param("page", "2").param("pageSize", "3"),
                "/api/v1/videos", "get", 200);
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("total").asLong()).isEqualTo(3);
        assertThat(body.get("totalPages").asLong()).isEqualTo(1);
        verify(vodMapper, never()).selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @Test
    void emptyResultWhenNoVisibleCategoryDoesNotQueryVideos() throws Exception {
        when(groupMapper.selectGroupPermission(1)).thenReturn(null);
        JsonNode body = call(get("/api/v1/videos"), "/api/v1/videos", "get", 200);
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("totalPages").asLong()).isZero();
        verify(vodMapper, never()).countPublished(any());
    }

    @Test
    void emptyStringParamsAreTreatedAsAbsent() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(0L);
        call(get("/api/v1/videos").param("page", "").param("pageSize", "").param("sort", "").param("order", "")
                .param("categoryId", ""), "/api/v1/videos", "get", 200);
    }

    @ParameterizedTest
    @CsvSource({
            "categoryId, 0", "categoryId, -1", "categoryId, abc", "categoryId, 65536", "categoryId, 1.5",
            "page, 0", "page, 1001", "page, x", "page, 99999999999",
            "pageSize, 0", "pageSize, 101", "pageSize, ' 5'",
            // sort=rnd 从契约 1.3.0（B04）起是合法取值，见 VideoRandomListTest
            "sort, RND", "sort, vod_time", "sort, TIME", "sort, 'time;drop'",
            "order, DESC", "order, up"
    })
    void invalidListParametersReturn400(String field, String value) throws Exception {
        JsonNode body = call(get("/api/v1/videos").param(field, value), "/api/v1/videos", "get", 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
        verify(vodMapper, never()).countPublished(any());
    }

    @ParameterizedTest
    @CsvSource({"3", "99"})
    void hiddenOrMissingCategoryReturns404(String categoryId) throws Exception {
        JsonNode body = call(get("/api/v1/videos").param("categoryId", categoryId), "/api/v1/videos", "get", 404);
        assertThat(body.get("code").asString()).isEqualTo("CATEGORY_NOT_FOUND");
    }

    @Test
    void listItemMarksPasswordProtectedVideoWithoutExposingPassword() throws Exception {
        var row = Fixtures.summary(5, 1);
        var locked = new com.maccms.backend.content.infrastructure.VodSummaryRow(row.id(), row.categoryId(),
                row.name(), row.subTitle(), row.enName(), row.letter(), row.color(), row.classes(), row.tag(),
                row.pic(), row.picThumb(), row.picSlide(), row.actor(), row.director(), row.blurb(), row.remarks(),
                row.area(), row.lang(), row.year(), row.version(), row.state(), row.serial(), row.total(),
                row.isEnd(), row.level(), row.copyright(), row.score(), row.scoreNum(), row.hits(), row.hitsDay(),
                row.hitsWeek(), row.hitsMonth(), row.up(), row.down(), row.updatedAt(), row.addedAt(), 1);
        when(vodMapper.countPublished(any())).thenReturn(1L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt())).thenReturn(List.of(locked));
        JsonNode body = call(get("/api/v1/videos"), "/api/v1/videos", "get", 200);
        assertThat(body.get("items").get(0).get("detailPasswordRequired").asBoolean()).isTrue();
    }

    // ---------- 列表：契约 1.1.0 搜索/筛选（application.yml 默认 maccms.content.search.enabled=false） ----------

    @Test
    void requestsWithoutNewParametersNeverUseMatchingStatements() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(1L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(1, 1)));
        call(get("/api/v1/videos").param("categoryId", "1").param("sort", "hits").param("page", "1"),
                "/api/v1/videos", "get", 200);
        // 新参数为空串 = 未传
        call(get("/api/v1/videos").param("keyword", "").param("area", "").param("lang", "").param("year", "")
                .param("level", "").param("classes", ""), "/api/v1/videos", "get", 200);
        verify(vodMapper, never()).countPublishedMatching(any(), any());
        verify(vodMapper, never()).selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @Test
    void legacyParameterNamesAreStillIgnored() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(2L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(2, 6), Fixtures.summary(1, 1)));
        String plain = mvc.perform(get("/api/v1/videos")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // actor 从契约 1.3.0（B04）起是正式参数，这里换成仍未声明的旧标签参数 isend
        String legacy = mvc.perform(get("/api/v1/videos?wd=x&class=y&tid=1&by=hits&isend=1")).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(legacy).isEqualTo(plain);
        verify(vodMapper, never()).countPublishedMatching(any(), any());
    }

    @Test
    void exactFiltersWorkWhileSearchIsDisabled() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(5, 6)));
        JsonNode body = call(get("/api/v1/videos").param("categoryId", "1").param("area", "大陆").param("lang", "国语")
                .param("year", "2024").param("level", "09").param("sort", "level").param("pageSize", "5"),
                "/api/v1/videos", "get", 200);
        assertThat(body.get("total").asLong()).isEqualTo(1);
        VodFilterCriteria expected = new VodFilterCriteria(9, "2024", "大陆", "国语", null, null);
        verify(vodMapper).countPublishedMatching(Set.of(1, 6, 7), expected);
        verify(vodMapper).selectPublishedMatchingPage(Set.of(1, 6, 7), expected, VideoSort.LEVEL, true, 0L, 5);
        verify(vodMapper, never()).countPublished(any());
    }

    /** 契约 1.3.0（MACC-28 Q1）：area/lang 逗号多值是精确匹配，开关关闭时也可用；只有一项时仍是 1.1.0 的单值条件。 */
    @Test
    void multiValueAreaAndLangWorkWhileSearchIsDisabled() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(5, 6)));
        call(get("/api/v1/videos").param("area", "大陆,香港,").param("lang", "国语").param("pageSize", "5"),
                "/api/v1/videos", "get", 200);
        VodFilterCriteria expected = new VodFilterCriteria(null, null, null, "国语", null, null, null, null, null, null,
                null, null, null, null, null, null, List.of("大陆", "香港", ""), null, null);
        verify(vodMapper).countPublishedMatching(any(), eq(expected));
        verify(vodMapper).selectPublishedMatchingPage(any(), eq(expected), eq(VideoSort.TIME), eq(true), eq(0L), eq(5));
    }

    @ParameterizedTest
    @CsvSource({"keyword, 合成", "classes, 剧情", "keyword, ' x'", "classes, 'a,b'", "classes, ','", "classes, '剧情,'"})
    void containsMatchParametersAreRejectedWhileSearchIsDisabled(String field, String value) throws Exception {
        JsonNode body = call(get("/api/v1/videos").param(field, value), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("当前未开放关键字搜索");
        verify(typeMapper, never()).selectEnabledVodTypes();
    }

    @Test
    void malformedUtf8FilterValueIsA400ForThatParameter() throws Exception {
        JsonNode body = call(get(java.net.URI.create("/api/v1/videos?keyword=%FF")), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("keyword");
        body = call(get(java.net.URI.create("/api/v1/videos?area=%E4%B8")), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("area");
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("含不允许的字符");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "area     | 'a,b '                | 首尾不能有空白字符",
            "lang     | ',a '                 | 首尾不能有空白字符",
            "lang     | 'a,b,c,d,e,f'         | 长度不能超过 10 个字符",
            "area     | ' a'                  | 首尾不能有空白字符",
            "area     | '　a'                 | 首尾不能有空白字符",
            "area     | 123456789012345678901 | 长度不能超过 20 个字符",
            "lang     | 12345678901           | 长度不能超过 10 个字符",
            "year     | 2020-202              | 必须是 4 位数字年份",
            "year     | ' 2020'               | 必须是 4 位数字年份",
            "level    | 0                     | 必须是 1 到 9 之间的整数",
            "level    | 10                    | 必须是 1 到 9 之间的整数",
            "level    | 00000000009           | 必须是 1 到 9 之间的整数",
    })
    void invalidFilterParametersReturn400WithFixedMessage(String field, String value, String message)
            throws Exception {
        JsonNode body = call(get("/api/v1/videos").param(field, value), "/api/v1/videos", "get", 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.get("fieldErrors")).hasSize(1);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo(message);
        verify(vodMapper, never()).countPublishedMatching(any(), any());
    }

    @Test
    void controlCharacterInFilterIsRejected() throws Exception {
        JsonNode body = call(get("/api/v1/videos").param("lang", "a\u0001"), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("含不允许的字符");
    }

    @ParameterizedTest
    @CsvSource({"area", "lang", "year", "level", "keyword", "classes"})
    void repeatedFilterParametersAreRejected(String field) throws Exception {
        for (String q : List.of(field + "=1&" + field + "=2", field + "=&" + field + "=")) {
            JsonNode body = call(get("/api/v1/videos?" + q), "/api/v1/videos", "get", 400);
            assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
            assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("参数不能重复");
        }
    }

    /** 1.0.0 的既有参数重复时维持原行为：逗号连接后按原规则判错（MACC-18 锁定）。 */
    @ParameterizedTest
    @CsvSource({"page=1&page=2, page", "pageSize=20&pageSize=, pageSize", "sort=time&sort=time, sort",
            "order=&order=, order", "categoryId=&categoryId=, categoryId"})
    void repeatedExistingParametersKeepContract100Behaviour(String query, String field) throws Exception {
        JsonNode body = call(get("/api/v1/videos?" + query), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
    }

    @Test
    void existingParametersAreValidatedFirstAndParameterErrorsComeBeforeVisibility() throws Exception {
        JsonNode body = call(get("/api/v1/videos").param("page", "0").param("area", " a"), "/api/v1/videos", "get",
                400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("page");
        body = call(get("/api/v1/videos").param("categoryId", "3").param("area", " a"), "/api/v1/videos", "get", 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("area");
        body = call(get("/api/v1/videos").param("categoryId", "3").param("area", "大陆"), "/api/v1/videos", "get", 404);
        assertThat(body.get("code").asString()).isEqualTo("CATEGORY_NOT_FOUND");
    }

    // ---------- 详情 ----------

    @Test
    void detailReturnsPublicMetadataOnly() throws Exception {
        when(vodMapper.selectPublishedDetail(10L)).thenReturn(Fixtures.detail(10, 6, 0, 0));
        JsonNode body = call(get("/api/v1/videos/10"), "/api/v1/videos/{id}", "get", 200);
        assertThat(body.get("id").asLong()).isEqualTo(10);
        assertThat(body.get("category").get("parentId").asInt()).isEqualTo(1);
        assertThat(body.get("score").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(body.get("finished").asBoolean()).isTrue();
        // 字段集合已由契约校验保证；这里再显式确认敏感/内部字段名不存在
        for (String forbidden : List.of("vod_pwd", "pwd", "password", "playUrl", "vod_play_url", "downUrl",
                "points", "pointsPlay", "trysee", "groupId", "tpl", "reurl", "jumpurl", "lock", "status")) {
            assertThat(body.has(forbidden)).as(forbidden).isFalse();
        }
    }

    @Test
    void detailNotFoundForMissingVideo() throws Exception {
        JsonNode body = call(get("/api/v1/videos/404"), "/api/v1/videos/{id}", "get", 404);
        assertThat(body.get("code").asString()).isEqualTo("VIDEO_NOT_FOUND");
    }

    @Test
    void detailNotFoundWhenCategoryHidden() throws Exception {
        when(vodMapper.selectPublishedDetail(11L)).thenReturn(Fixtures.detail(11, 3, 0, 0));
        JsonNode body = call(get("/api/v1/videos/11"), "/api/v1/videos/{id}", "get", 404);
        assertThat(body.get("code").asString()).isEqualTo("VIDEO_NOT_FOUND");
    }

    @Test
    void detailNotFoundWhenGuestLacksDetailPermission() throws Exception {
        // 分类 7 游客只有列表位 1，没有详情位 2
        when(vodMapper.selectPublishedDetail(12L)).thenReturn(Fixtures.detail(12, 7, 0, 0));
        JsonNode body = call(get("/api/v1/videos/12"), "/api/v1/videos/{id}", "get", 404);
        assertThat(body.get("code").asString()).isEqualTo("VIDEO_NOT_FOUND");
    }

    @Test
    void detailForbiddenWhenPasswordRequired() throws Exception {
        when(vodMapper.selectPublishedDetail(13L)).thenReturn(Fixtures.detail(13, 1, 1, 0));
        JsonNode body = call(get("/api/v1/videos/13"), "/api/v1/videos/{id}", "get", 403);
        assertThat(body.get("code").asString()).isEqualTo("VIDEO_PASSWORD_REQUIRED");
        assertThat(body.toString()).doesNotContain("视频13");
    }

    @Test
    void detailForbiddenWhenCopyrightRestricted() throws Exception {
        when(vodMapper.selectPublishedDetail(14L)).thenReturn(Fixtures.detail(14, 1, 0, 1));
        JsonNode body = call(get("/api/v1/videos/14"), "/api/v1/videos/{id}", "get", 403);
        assertThat(body.get("code").asString()).isEqualTo("VIDEO_COPYRIGHT_RESTRICTED");
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "abc", "1.0", "4294967296", "99999999999", "%20"})
    void invalidVideoIdReturns400(String id) throws Exception {
        JsonNode body = call(get("/api/v1/videos/" + id), "/api/v1/videos/{id}", "get", 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("id");
        verify(vodMapper, never()).selectPublishedDetail(anyLong());
    }

    @Test
    void maxVideoIdIsAccepted() throws Exception {
        call(get("/api/v1/videos/4294967295"), "/api/v1/videos/{id}", "get", 404);
        verify(vodMapper).selectPublishedDetail(4294967295L);
    }

    // ---------- 通用 ----------

    @Test
    void unknownPathReturnsJson404() throws Exception {
        JsonNode body = call(get("/api/v1/nope"), null, null, 404);
        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void writeMethodsAreRejected() throws Exception {
        JsonNode body = call(post("/api/v1/videos"), null, null, 405);
        assertThat(body.get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    // 失败路径：响应和日志都不能出现异常 message（含嵌套 cause / suppressed）。标记是合成值，不是真实配置。

    private static final String LEAK = "SYNTHETIC-LEAK-MARKER";

    private JsonNode failWith(RuntimeException e, int status, CapturedOutput output) throws Exception {
        when(typeMapper.selectEnabledVodTypes()).thenThrow(e);
        MvcResult r = mvc.perform(get("/api/v1/video-categories")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(LEAK).doesNotContain("jdbc").doesNotContain("Exception");
        contract.assertMatches(JSON.readTree(text), contract.responseSchema("/api/v1/video-categories", "get", status));
        assertThat(output.getAll()).as("日志").doesNotContain(LEAK);
        return JSON.readTree(text);
    }

    @Test
    void databaseFailureReturns503WithoutDetailsInResponseOrLog(CapturedOutput output) throws Exception {
        java.sql.SQLException cause = new java.sql.SQLException(LEAK + "-cause user=synthetic_user");
        JsonNode body = failWith(new org.springframework.dao.DataAccessResourceFailureException(
                "jdbc:mysql://" + LEAK + "-host/db password=" + LEAK, cause), 503, output);
        assertThat(body.get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
        // 日志仍有定位价值：外层与 cause 的类型
        assertThat(output.getAll()).contains("database unavailable")
                .contains("org.springframework.dao.DataAccessResourceFailureException <- java.sql.SQLException");
    }

    /**
     * MACC-14：连接类故障改由 content.application 转换为 ApiException(SERVICE_UNAVAILABLE)，三个接口的 503 响应体
     * 与原 GlobalExceptionHandler 的 DataAccessResourceFailureException 分支逐字相同。真实连接失败被 MyBatis 包装后
     * 外层是 MyBatisSystemException，改动前落到 500（与契约"数据库暂时不可用 → 503"不符），改动后为 503。
     */
    @ParameterizedTest
    @CsvSource({"/api/v1/video-categories", "/api/v1/videos", "/api/v1/videos?categoryId=1", "/api/v1/videos/1"})
    void connectionFailureKeepsExact503Body(String path, CapturedOutput output) throws Exception {
        // 与真实连接失败相同的包装：MyBatisSystemException <- PersistenceException <- CannotGetJdbcConnectionException
        RuntimeException e = new org.mybatis.spring.MyBatisSystemException(LEAK,
                new org.apache.ibatis.exceptions.PersistenceException(LEAK,
                        new org.springframework.jdbc.CannotGetJdbcConnectionException("jdbc:mysql://" + LEAK + "-host/db",
                                new java.sql.SQLTransientConnectionException(LEAK))));
        when(typeMapper.selectEnabledVodTypes()).thenThrow(e);
        when(vodMapper.selectPublishedDetail(anyLong())).thenThrow(e);
        MvcResult r = mvc.perform(get(path)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂时不可用\",\"fieldErrors\":[]}");
        assertThat(output.getAll()).contains("database unavailable: org.mybatis.spring.MyBatisSystemException")
                .contains("org.springframework.jdbc.CannotGetJdbcConnectionException").doesNotContain(LEAK);
    }

    @Test
    void plainDataAccessFailureReturns500WithoutDetailsInResponseOrLog(CapturedOutput output) throws Exception {
        java.sql.SQLException root = new java.sql.SQLException(LEAK + "-root");
        java.sql.SQLException cause = new java.sql.SQLSyntaxErrorException(LEAK + "-sql-error", root);
        cause.addSuppressed(new IllegalStateException(LEAK + "-suppressed"));
        JsonNode body = failWith(new org.springframework.jdbc.BadSqlGrammarException(
                LEAK + "-task", "SELECT * FROM mac_vod WHERE vod_pwd = '" + LEAK + "'", cause), 500, output);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(output.getAll()).contains("database access failed")
                .contains("org.springframework.jdbc.BadSqlGrammarException")
                .contains("Caused by: java.sql.SQLSyntaxErrorException")
                .contains("Caused by: java.sql.SQLException")
                .contains("Suppressed: java.lang.IllegalStateException")
                .contains("	at ");
    }

    @Test
    void unexpectedFailureReturns500WithoutDetailsInResponseOrLog(CapturedOutput output) throws Exception {
        RuntimeException e = new IllegalStateException(LEAK + "-outer",
                new IllegalArgumentException(LEAK + "-nested", new RuntimeException(LEAK + "-deepest")));
        JsonNode body = failWith(e, 500, output);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(output.getAll()).contains("unhandled exception")
                .contains("java.lang.IllegalStateException")
                .contains("Caused by: java.lang.IllegalArgumentException")
                .contains("Caused by: java.lang.RuntimeException");
    }

    @Test
    void contractDeclaresOnlyGetOperations() {
        for (Map.Entry<String, Object> e : contract.paths().entrySet()) {
            assertThat(contract.map(e.getValue()).keySet()).as(e.getKey()).containsOnly("get");
        }
    }
}
