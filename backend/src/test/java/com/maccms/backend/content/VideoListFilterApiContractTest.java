package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import com.maccms.backend.content.infrastructure.VodIdRange;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 契约 1.3.0（B04）视频列表新参数的 HTTP 契约（搜索开启，并发上限 1）：参数到 Mapper 条件的映射、固定错误文案与校验顺序、
 * actor/director/tag/name 与 keyword/classes 共用并发许可（满时 503、结束后归还）、精确条件与随机排序不占许可、
 * 服务端执行时限 → 503（响应体与数据库不可用逐字相同）、sort=rnd 的响应形状。Mapper 为替身；SQL 见 VodMapperBoundSqlTest，
 * 真实 MySQL 与旧版对照见 VideoListGoldenMySqlIT。
 */
@WebMvcTest(controllers = {VideoController.class, VideoCategoryController.class})
@Import(ContentReadService.class)
@EnableConfigurationProperties(ContentProperties.class)
@TestPropertySource(properties = {"maccms.content.search.enabled=true", "maccms.content.search.max-concurrent=1"})
@ExtendWith(OutputCaptureExtension.class)
class VideoListFilterApiContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNAVAILABLE =
            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂时不可用\",\"fieldErrors\":[]}";
    /** 合成标记，不是真实配置。 */
    private static final String LEAK = "SYNTHETIC-LEAK-b04a";
    /** Fixtures.standardTypes 中游客可见的全部分类。 */
    private static final Set<Integer> VISIBLE = Set.of(1, 6, 7, 2, 13);
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

    private MvcResult perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, int status) throws Exception {
        MvcResult r = perform(req);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(status);
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        contract.assertMatches(body, contract.responseSchema("/api/v1/videos", "get", status));
        return body;
    }

    private VodFilterCriteria capturedCriteria() {
        ArgumentCaptor<VodFilterCriteria> c = ArgumentCaptor.forClass(VodFilterCriteria.class);
        verify(vodMapper).countPublishedMatching(eq(VISIBLE), c.capture());
        return c.getValue();
    }

    // ------------------------------------------------------------------ 映射

    @Test
    void newParametersBecomeListConditions() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(9, 13)));
        call(get("/api/v1/videos").param("letter", "0-9").param("state", "正片,").param("version", "高清")
                .param("actor", "甲,Tom & Jerry").param("director", "乙").param("tag", "100%").param("name", "丁")
                .param("ids", "9,,10").param("year", "2020-2018"), 200);
        VodFilterCriteria c = capturedCriteria();
        assertThat(c.letters()).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(c.states()).containsExactly("正片", "");
        assertThat(c.versions()).containsExactly("高清");
        assertThat(c.actorPatterns()).containsExactly("%甲%", "%Tom & Jerry%", "%Tom &amp; Jerry%");
        assertThat(c.directorPatterns()).containsExactly("%乙%");
        assertThat(c.tagPatterns()).containsExactly("%100!%%");
        assertThat(c.namePatterns()).containsExactly("%丁%");
        assertThat(c.ids()).containsExactly(9L, 0L, 10L);
        assertThat(c.years()).containsExactly("2018", "2019", "2020");
        assertThat(c.year()).isNull();
        verify(vodMapper).selectPublishedMatchingPage(VISIBLE, c, VideoSort.TIME, true, 0L, 20);
        verify(vodMapper, never()).countPublished(any());
    }

    @Test
    void contract110ParametersKeepTheirCriteriaWhenCombined() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        call(get("/api/v1/videos").param("area", "大陆").param("year", "2020").param("letter", "A"), 200);
        VodFilterCriteria c = capturedCriteria();
        assertThat(c.area()).isEqualTo("大陆");
        assertThat(c.year()).as("单个年份仍是 1.1.0 的 vod_year = ?").isEqualTo("2020");
        assertThat(c.years()).isNull();
        assertThat(c.letters()).containsExactly("A");
    }

    @Test
    void idsAllIsTheContract100QueryAndLegacyNamesAreStillIgnored() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(1L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(1, 1)));
        String plain = perform(get("/api/v1/videos")).getResponse().getContentAsString(StandardCharsets.UTF_8);
        String all = perform(get("/api/v1/videos").param("ids", "all")).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String legacy = perform(get("/api/v1/videos?isend=1&timeadd=x&not=1&rel=2&typenot=1&weekday=x&tv=x&plot=1"
                + "&start=2&half=1&hits=10&tid=1")).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(all).isEqualTo(plain);
        assertThat(legacy).as("标签专属条件不是 URL 参数（B05 内部能力），继续被忽略").isEqualTo(plain);
        verify(vodMapper, never()).countPublishedMatching(any(), any());
    }

    // ------------------------------------------------------------------ 错误

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "letter   | ' A'          | 首尾不能有空白字符",
            "state    | 'a\u0001'     | 含不允许的字符",
            "version  | ' v'          | 首尾不能有空白字符",
            "actor    | '甲 '         | 首尾不能有空白字符",
            "ids      | a             | 必须是 all 或以逗号分隔的视频 ID",
            "ids      | 4294967296    | 必须是 all 或以逗号分隔的视频 ID",
            "ids      | 1;2           | 必须是 all 或以逗号分隔的视频 ID",
            "year     | 2020-202      | 必须是 4 位数字年份",
            "year     | 2020年-2022   | 必须是 4 位数字年份",
    })
    void invalidNewParametersReturn400WithFixedMessage(String field, String value, String message) throws Exception {
        JsonNode body = call(get("/api/v1/videos").param(field, value), 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo(message);
        verify(typeMapper, never()).selectEnabledVodTypes();
    }

    @Test
    void moreThanOneHundredIdsIs400() throws Exception {
        String hundred = String.join(",", Collections.nCopies(100, "7"));
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        call(get("/api/v1/videos").param("ids", hundred), 200);
        JsonNode body = call(get("/api/v1/videos").param("ids", hundred + ",8"), 400);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("最多 100 个 ID");
    }

    @Test
    void validationOrderPutsNewParametersAfterTheContract110Ones() throws Exception {
        JsonNode body = call(get("/api/v1/videos").param("ids", "x").param("letter", " A").param("classes", " c"), 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("classes");
        body = call(get("/api/v1/videos").param("ids", "x").param("name", " n").param("letter", " A"), 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("letter");
        body = call(get("/api/v1/videos").param("ids", "x").param("name", " n").param("page", "0"), 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).as("既有参数最先").isEqualTo("page");
    }

    @Test
    void malformedUtf8NewParameterIs400() throws Exception {
        for (String q : List.of("letter=%FF", "ids=%E4%B8", "actor=a%FFb")) {
            JsonNode body = call(get(URI.create("/api/v1/videos?" + q)), 400);
            assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(q.substring(0, q.indexOf('=')));
            assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("含不允许的字符");
        }
    }

    // ------------------------------------------------------------------ 并发许可

    @Test
    void listContainsParametersShareThePermitAndExactOnesDoNot(CapturedOutput output) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(vodMapper.countPublishedMatching(any(), any())).thenAnswer(inv -> {
            VodFilterCriteria c = inv.getArgument(1);
            if (c.actorPatterns() != null) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return 0L;
        });
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return perform(get("/api/v1/videos").param("actor", "占位"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            for (String p : List.of("keyword", "classes", "actor", "director", "tag", "name")) {
                MvcResult second = perform(get("/api/v1/videos").param(p, LEAK));
                assertThat(second.getResponse().getStatus()).as(p).isEqualTo(503);
                assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
            }
            // 精确条件与随机排序不受并发上限约束
            for (String[] q : new String[][]{{"letter", "A"}, {"state", "正片"}, {"version", "v"}, {"ids", "1"},
                    {"year", "2018-2020"}}) {
                call(get("/api/v1/videos").param(q[0], q[1]), 200);
            }
            when(vodMapper.countPublished(any())).thenReturn(0L);
            call(get("/api/v1/videos").param("sort", "rnd"), 200);
        } finally {
            release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        // 许可已归还
        call(get("/api/v1/videos").param("tag", "再次"), 200);
        assertThat(output.getAll()).contains("content search rejected: concurrency limit reached")
                .doesNotContain(LEAK).doesNotContain("占位");
    }

    // ------------------------------------------------------------------ 执行时限

    @Test
    void statementTimeoutIs503ForEveryNewCondition(CapturedOutput output) throws Exception {
        SQLException cause = new SQLException(LEAK + " maximum statement execution time exceeded", "HY000", 3024);
        when(vodMapper.countPublishedMatching(any(), any())).thenThrow(new UncategorizedSQLException(LEAK,
                "SELECT /*+ MAX_EXECUTION_TIME(3000) */ " + LEAK, cause));
        for (String[] q : new String[][]{{"letter", "A"}, {"state", "s"}, {"version", "v"}, {"actor", "a"},
                {"director", "d"}, {"tag", "t"}, {"name", "n"}, {"ids", "1"}, {"year", "2018-2020"}}) {
            MvcResult r = perform(get("/api/v1/videos").param(q[0], q[1]));
            assertThat(r.getResponse().getStatus()).as(q[0]).isEqualTo(503);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
        }
        assertThat(output.getAll()).contains("database statement timeout").doesNotContain(LEAK)
                .doesNotContain("MAX_EXECUTION_TIME");
        // 许可在超时后同样归还
        doReturn(0L).when(vodMapper).countPublishedMatching(any(), any());
        call(get("/api/v1/videos").param("actor", "a"), 200);
    }

    @Test
    void randomIdRangeTimeoutIs503() throws Exception {
        when(vodMapper.countPublished(any())).thenReturn(5000L);
        when(vodMapper.selectIdRange()).thenThrow(new UncategorizedSQLException(LEAK, "SELECT " + LEAK,
                new SQLException(LEAK, "HY000", 3024)));
        MvcResult r = perform(get("/api/v1/videos").param("sort", "rnd"));
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
    }

    // ------------------------------------------------------------------ sort=rnd

    @Test
    void randomSortSmallResultReturnsTheRandomPage() throws Exception {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(0L);
        JsonNode body = call(get("/api/v1/videos").param("sort", "rnd").param("order", "asc").param("page", "9"), 200);
        assertThat(body.get("page").asInt()).as("c=0 时唯一的随机页是 1，与请求的 page 无关").isEqualTo(1);
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
        verify(vodMapper, never()).selectIdRange();
    }

    @Test
    void randomSortLargeResultUsesTheCandidateRangeAndTheRequestedPage() throws Exception {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(2001L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(1L, 3001L));
        when(vodMapper.countPublishedMatching(eq(VISIBLE), any())).thenReturn(3L);
        when(vodMapper.selectPublishedMatchingPage(eq(VISIBLE), any(), eq(VideoSort.HITS_WEEK), eq(true), eq(2L),
                eq(2))).thenReturn(List.of(Fixtures.summary(5, 1)));
        JsonNode body = call(get("/api/v1/videos").param("sort", "rnd").param("page", "2").param("pageSize", "2"), 200);
        assertThat(body.get("page").asInt()).isEqualTo(2);
        assertThat(body.get("total").asLong()).isEqualTo(3);
        assertThat(body.get("items").get(0).get("id").asLong()).isEqualTo(5);
        VodFilterCriteria c = capturedCriteria();
        assertThat(c.candidateIds()).hasSize(1000).allSatisfy(id -> assertThat(id).isBetween(0L, 3000L));
        assertThat(c.candidateIds()).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void searchSwitchOffIsCheckedBeforeTheRandomQuery() throws Exception {
        // 本类开关打开；关闭时的 400 见 VideoListFilterSearchOffApiTest
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        call(get("/api/v1/videos").param("sort", "rnd").param("director", "乙"), 200);
    }
}
