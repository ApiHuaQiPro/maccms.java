package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.maccms.backend.content.infrastructure.VodMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 契约 1.1.0 搜索开启时的 HTTP 契约（maccms.content.search.enabled=true，并发上限 1）：包含匹配参数生效、
 * 非法 UTF-8 → 400、并发上限与服务端执行时限 → 503（响应体与数据库不可用逐字相同），以及这两个新 503 分支的日志脱敏。
 * Mapper 为 Mockito 替身；SQL 与真实 MySQL 行为见 VodMapperBoundSqlTest、ContentSearchMySqlIT。
 */
@WebMvcTest(controllers = {VideoController.class, VideoCategoryController.class})
@Import(ContentReadService.class)
@EnableConfigurationProperties(ContentProperties.class)
@TestPropertySource(properties = {"maccms.content.search.enabled=true", "maccms.content.search.max-concurrent=1"})
@ExtendWith(OutputCaptureExtension.class)
class ContentSearchApiContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNAVAILABLE =
            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂时不可用\",\"fieldErrors\":[]}";
    /** 合成标记，不是真实配置。 */
    private static final String LEAK = "SYNTHETIC-LEAK-7c1e";
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

    private MvcResult perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, int status) throws Exception {
        MvcResult r = perform(req);
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        contract.assertMatches(body, contract.responseSchema("/api/v1/videos", "get", status));
        return body;
    }

    @Test
    void keywordAndClassesBecomeEscapedLikePatterns() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(9, 13)));
        JsonNode body = call(get("/api/v1/videos").param("keyword", "Tom & 100%_!").param("classes", "剧情")
                .param("categoryId", "2").param("year", "2024"), 200);
        assertThat(body.get("items").get(0).get("category").get("id").asInt()).isEqualTo(13);
        VodFilterCriteria expected = new VodFilterCriteria(null, "2024", null, null, "%剧情%",
                List.of("%Tom & 100!%!_!!%", "%Tom &amp; 100!%!_!!%"));
        verify(vodMapper).countPublishedMatching(Set.of(2, 13), expected);
        verify(vodMapper).selectPublishedMatchingPage(Set.of(2, 13), expected, VideoSort.TIME, true, 0L, 20);
    }

    @Test
    void noResultIsAnEmptyPageNotA404() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        JsonNode body = call(get("/api/v1/videos").param("keyword", "无结果"), 200);
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("totalPages").asLong()).isZero();
        verify(vodMapper, never()).selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @Test
    void malformedUtf8KeywordIsRejectedAsBadCharacter() throws Exception {
        for (String q : List.of("keyword=%FF", "keyword=%E4%B8", "keyword=a%FFb", "classes=%C0%AF")) {
            JsonNode body = call(get(URI.create("/api/v1/videos?" + q)), 400);
            assertThat(body.get("fieldErrors").get(0).get("field").asString()).as(q).isIn("keyword", "classes");
            assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("含不允许的字符");
        }
        verify(typeMapper, never()).selectEnabledVodTypes();
    }

    @Test
    void containsMatchConcurrencyLimitReturns503AndLeavesExactFiltersAlone(CapturedOutput output) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(vodMapper.countPublishedMatching(any(), any())).thenAnswer(inv -> {
            VodFilterCriteria c = inv.getArgument(1);
            if (c.keywordPatterns() != null) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return 0L;
        });
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return perform(get("/api/v1/videos").param("keyword", "占位"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // 上限 1 已被占用：第二个包含匹配请求立即 503，响应体与数据库不可用逐字相同
            for (String p : List.of("keyword", "classes")) {
                MvcResult second = perform(get("/api/v1/videos").param(p, LEAK));
                assertThat(second.getResponse().getStatus()).isEqualTo(503);
                assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
            }
            // 精确筛选与无条件列表不受并发上限约束
            call(get("/api/v1/videos").param("area", "大陆"), 200);
            when(vodMapper.countPublished(any())).thenReturn(0L);
            call(get("/api/v1/videos"), 200);
        } finally {
            release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        // 许可已释放
        call(get("/api/v1/videos").param("keyword", "再次"), 200);
        assertThat(output.getAll()).contains("content search rejected: concurrency limit reached")
                .doesNotContain(LEAK).doesNotContain("占位");
    }

    @Test
    void serverStatementTimeoutReturns503WithoutDetailsInResponseOrLog(CapturedOutput output) throws Exception {
        // 真实 MySQL 5.7 下 MAX_EXECUTION_TIME 超时被 Spring 翻译为 UncategorizedSQLException <- SQLException(3024)
        // （ContentSearchMySqlIT 实测）；message 中的合成标记不得进入响应或日志
        SQLException cause = new SQLException(LEAK + " maximum statement execution time exceeded", "HY000", 3024);
        assertTimeoutIs503(new UncategorizedSQLException(LEAK + "-task",
                "SELECT /*+ MAX_EXECUTION_TIME(3000) */ " + LEAK, cause), output);
    }

    @Test
    void filesortAbortedByStatementTimeoutReturnsTheSame503(CapturedOutput output) throws Exception {
        // 时限在排序阶段触发时 MySQL 5.7.44 报 1028 ER_FILSORT_ABORT（MACC-19 性能报告第 6.3 节，修复前为 500）；
        // 响应与日志须与 3024 分支逐字一致
        SQLException cause = new SQLException(LEAK + " Sort aborted: Query execution was interrupted, "
                + "maximum statement execution time exceeded", "HY000", 1028);
        assertTimeoutIs503(new UncategorizedSQLException(LEAK + "-task",
                "SELECT /*+ MAX_EXECUTION_TIME(3000) */ " + LEAK + " ORDER BY vod_time DESC", cause), output);
    }

    private void assertTimeoutIs503(UncategorizedSQLException timeout, CapturedOutput output) throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenThrow(timeout);
        for (String p : List.of("area", "keyword", "classes")) {
            MvcResult r = perform(get("/api/v1/videos").param(p, LEAK.substring(0, 10)));
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
        }
        assertThat(output.getAll()).contains("database statement timeout: org.springframework.jdbc"
                + ".UncategorizedSQLException <- java.sql.SQLException").doesNotContain(LEAK)
                .doesNotContain(LEAK.substring(0, 10)).doesNotContain("MAX_EXECUTION_TIME")
                .doesNotContain("Sort aborted").doesNotContain("maximum statement execution time")
                .doesNotContain("database access failed");
    }

    @Test
    void otherSqlErrorsInFilteredQueriesStay500(CapturedOutput output) throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenThrow(new BadSqlGrammarException(LEAK,
                "SELECT " + LEAK, new SQLException(LEAK, "42000", 1064)));
        MvcResult r = perform(get("/api/v1/videos").param("area", "大陆"));
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(LEAK);
        assertThat(output.getAll()).contains("database access failed").doesNotContain(LEAK)
                .doesNotContain("database statement timeout");
    }
}
