package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.openapi.api.ProvideController;
import com.maccms.backend.openapi.application.ProvideSettings;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
import com.maccms.backend.openapi.config.OpenApiConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Provide 的路由、开关、参数规则与状态码（B06，16 文档第 3.1–3.4 节）。数据读取是替身，断言交给 content 的查询（{@link LegacyRowQuery}）
 * 与旧版参数语义一致；输出的逐字节对照见 ProvideGoldenMySqlIT。时钟固定在 2026-10-17 12:00:00（北京时间 20:00）。
 */
@WebMvcTest(controllers = ProvideController.class, properties = {"maccms.openapi.enabled=true"})
@Import({OpenApiConfiguration.class, ClientIpConfiguration.class, ProvideApiTest.Beans.class})
class ProvideApiTest {

    static final AtomicReference<ProvideSettings> SETTINGS = new AtomicReference<>();
    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @TestConfiguration
    static class Beans {
        @Bean
        ProvideSettingsSource testSettings() {
            return SETTINGS::get;
        }

        @Bean
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    LegacyRowReadService rows;
    @MockitoBean
    LegacyConfigService legacy;
    /** B27：OpenApiConfiguration 同时装配微信公众号接口，它的视频搜索经 content 读取服务。 */
    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void setUp() {
        SETTINGS.set(settings(""));
        when(rows.read(any())).thenReturn(ProvideHttp.empty());
        when(rows.types()).thenReturn(List.of());
    }

    /** vod/art/actor/role/website 全部打开、不收费；vod 另有给定的 typefilter。 */
    static ProvideSettings settings(String vodExtra) {
        String php = "<?php\nreturn array ('api' => array (\n"
                + "'vod' => array ('status' => 1, 'charge' => '0', 'pagesize' => '20', 'datafilter' => ' vod_status=1'"
                + vodExtra + "),\n"
                + "'art' => array ('status' => '1', 'pagesize' => '20', 'typefilter' => '21'),\n"
                + "'actor' => array ('status' => '1', 'pagesize' => '20'),\n"
                + "'role' => array ('status' => '1', 'pagesize' => '20'),\n"
                + "'website' => array ('status' => '1', 'pagesize' => '20')));\n";
        return ProvideSettings.from(com.maccms.backend.legacyconfig.application.VarExportParser.parse(
                php.getBytes(StandardCharsets.UTF_8)));
    }

    /** 原样发送路径与查询串（不经 URI 模板再编码）。 */
    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(java.net.URI.create(path))).andReturn();
    }

    private LegacyRowQuery query(String path) throws Exception {
        MvcResult r = fetch(path);
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        ArgumentCaptor<LegacyRowQuery> q = ArgumentCaptor.forClass(LegacyRowQuery.class);
        verify(rows, org.mockito.Mockito.atLeastOnce()).read(q.capture());
        org.mockito.Mockito.clearInvocations(rows);
        return q.getValue();
    }

    private void assertBadRequest(String path) throws Exception {
        MvcResult r = fetch(path);
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("参数错误");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void routesSuffixesAndPathParameters() throws Exception {
        for (String p : List.of("/api.php/provide/vod", "/api.php/provide/vod/", "/api.php/provide/vod.html",
                "/api.php/provide/vod.xml", "/api.php/provide/vod/ac/list", "/api.php/provide/vod/at/xml/")) {
            assertThat(fetch(p).getResponse().getStatus()).as(p).isEqualTo(200);
        }
        assertThat(fetch("/api.php/provide/vod/at/xml/").getResponse().getContentType())
                .isEqualTo("text/xml; charset=utf-8");
        assertThat(fetch("/api.php/provide/nope").getResponse().getStatus()).isEqualTo(404);
        assertThat(fetch("/api.php/provide/VOD").getResponse().getStatus()).isEqualTo(404);
        assertThat(fetch("/api.php/other/vod").getResponse().getStatus()).isEqualTo(404);
        // comment、index：旧方法体为空
        for (String p : List.of("/api.php/provide/comment", "/api.php/provide/index/")) {
            MvcResult r = fetch(p);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
        }
    }

    @Test
    void pathParametersOverridePostAndPostOverridesQuery() throws Exception {
        mvc.perform(post("/api.php/provide/vod/ids/3?ids=1").param("ids", "2")).andReturn();
        ArgumentCaptor<LegacyRowQuery> q = ArgumentCaptor.forClass(LegacyRowQuery.class);
        verify(rows).read(q.capture());
        assertThat(q.getValue().ids()).containsExactly("3");
        org.mockito.Mockito.clearInvocations(rows);
        mvc.perform(post("/api.php/provide/vod/?ids=1").param("ids", "2")).andReturn();
        verify(rows).read(q.capture());
        assertThat(q.getValue().ids()).containsExactly("2");
    }

    @Test
    void headHasHeadersOnly() throws Exception {
        // MockMvc 不去掉 HEAD 的正文（真实容器会去掉，ProvideRealHttpTest 用原始套接字验证）；这里只核对状态与头
        MvcResult r = mvc.perform(head("/api.php/provide/vod/?ac=list")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getContentType()).isEqualTo("application/json; charset=utf-8");
    }

    @Test
    void closedModulesAndNoLegacyConfig() throws Exception {
        SETTINGS.set(ProvideSettings.closed());
        for (String m : List.of("vod", "art", "actor", "role", "website")) {
            MvcResult r = fetch("/api.php/provide/" + m + "/?ac=list");
            assertThat(r.getResponse().getContentAsString()).isEqualTo("closed");
            assertThat(r.getResponse().getContentType()).isEqualTo("text/plain; charset=utf-8");
        }
        verify(rows, never()).read(any());
    }

    @Test
    void listAndDetailColumnsAndOrder() throws Exception {
        LegacyRowQuery q = query("/api.php/provide/vod/?ac=list");
        assertThat(q.table()).isEqualTo(LegacyTable.VOD);
        assertThat(q.columns()).isEqualTo(LegacyColumnSet.LIST);
        assertThat(q.order()).isEqualTo(LegacyRowOrder.TIME_DESC);
        assertThat(q.conditions()).hasSize(1);
        assertThat(query("/api.php/provide/vod/?ac=videolist").columns()).isEqualTo(LegacyColumnSet.FULL);
        assertThat(query("/api.php/provide/vod/?ac=detail&sort_direction=asc").order()).isEqualTo(LegacyRowOrder.TIME_ASC);
        assertThat(query("/api.php/provide/vod/?ac=detail&sort_direction=ASC").order()).isEqualTo(LegacyRowOrder.TIME_DESC);
        // 其余模块只认 detail；角色不使用 datafilter（Role.php:37-47）
        assertThat(query("/api.php/provide/art/?ac=videolist").columns()).isEqualTo(LegacyColumnSet.LIST);
        assertThat(query("/api.php/provide/role/?ac=detail").conditions()).isNull();
    }

    @Test
    void pagingFollowsTheLegacyRules() throws Exception {
        LegacyRowQuery q = query("/api.php/provide/vod/?pg=3&pagesize=7");
        assertThat(q.offset()).isEqualTo(14);
        assertThat(q.limit()).isEqualTo(7);
        assertThat(query("/api.php/provide/vod/?pagesize=500").limit()).isEqualTo(100);
        assertThat(query("/api.php/provide/vod/?pagesize=0").limit()).isEqualTo(20);
        assertThat(query("/api.php/provide/vod/?pg=0").offset()).isZero();
        assertThat(query("/api.php/provide/art/?pg=abc").offset()).isZero();
        assertThat(query("/api.php/provide/art/?pagesize=5").limit()).as("pagesize 只用于视频").isEqualTo(20);
        assertBadRequest("/api.php/provide/vod/?pg=abc");
        assertBadRequest("/api.php/provide/vod/?pg=-1");
        assertBadRequest("/api.php/provide/art/?pg=2abc");
        assertBadRequest("/api.php/provide/vod/?pagesize=abc");
        assertBadRequest("/api.php/provide/vod/?pg=1000001");
    }

    @Test
    void typeFilterAndT() throws Exception {
        SETTINGS.set(settings(", 'typefilter' => '2,11'"));
        assertThat(query("/api.php/provide/vod/").typeIdIn()).containsExactly(2, 11);
        // t 是 typefilter 的子串（旧 strpos）时覆盖 typefilter 的 IN
        LegacyRowQuery q = query("/api.php/provide/vod/?t=1");
        assertThat(q.typeIdEquals()).isEqualTo("1");
        assertThat(q.typeIdIn()).isNull();
        q = query("/api.php/provide/vod/?t=4");
        assertThat(q.typeIdEquals()).isNull();
        assertThat(q.typeIdIn()).containsExactly(2, 11);
        // 其余模块的 typefilter 不限定行
        assertThat(query("/api.php/provide/art/").typeIdIn()).isNull();
        assertThat(query("/api.php/provide/art/?t=3").typeIdEquals()).isNull();
        assertThat(query("/api.php/provide/art/?t=21").typeIdEquals()).isEqualTo("21");
        // 旧版按子串判定：typefilter '21' 包含 '2'，t=2 照样生效
        assertThat(query("/api.php/provide/art/?t=2").typeIdEquals()).isEqualTo("2");
        assertBadRequest("/api.php/provide/role/?t=1");
    }

    @Test
    void isEndUsesPhp8LooseComparison() throws Exception {
        assertThat(query("/api.php/provide/vod/?isend=1").isEnd()).isEqualTo(1);
        assertThat(query("/api.php/provide/vod/?isend=1.0").isEnd()).isEqualTo(1);
        assertThat(query("/api.php/provide/vod/?isend=01").isEnd()).isEqualTo(1);
        assertThat(query("/api.php/provide/vod/?isend=").isEnd()).isEqualTo(0);
        assertThat(query("/api.php/provide/vod/?isend=abc").isEnd()).isEqualTo(0);
        assertThat(query("/api.php/provide/vod/?isend=1abc").isEnd()).as("PHP 8").isEqualTo(0);
        assertThat(query("/api.php/provide/vod/").isEnd()).isNull();
    }

    @Test
    void hoursAreRelativeToBeijingTime() throws Exception {
        long now = NOW.getEpochSecond();
        long tomorrow = Instant.parse("2026-10-17T16:00:00Z").getEpochSecond(); // 北京时间 2026-10-18 00:00
        LegacyRowQuery q = query("/api.php/provide/vod/?h=24");
        assertThat(q.timeAfter()).isEqualTo(now - 24 * 3600);
        assertThat(q.timeBefore()).isEqualTo(tomorrow);
        q = query("/api.php/provide/art/?h=24");
        // 其余模块按下限当日 0 点（北京时间 2026-10-16 00:00）
        assertThat(q.timeAfter()).isEqualTo(Instant.parse("2026-10-15T16:00:00Z").getEpochSecond());
        assertThat(query("/api.php/provide/vod/?h=0").timeAfter()).isNull();
        assertThat(query("/api.php/provide/vod/?h=00").timeAfter()).isEqualTo(now);
        assertThat(query("/api.php/provide/art/?h=00").timeAfter()).isNull();
        assertThat(query("/api.php/provide/art/?h=abc").timeAfter()).as("intval 为 0").isNull();
        assertBadRequest("/api.php/provide/vod/?h=abc");
        assertBadRequest("/api.php/provide/art/?h=5abc");
    }

    @Test
    void yearsFollowTheLegacyLengthRules() throws Exception {
        assertThat(query("/api.php/provide/vod/?year=2014").years()).containsExactly("2014");
        assertThat(query("/api.php/provide/vod/?year=02a0").years()).containsExactly("2");
        assertThat(query("/api.php/provide/vod/?year=2014-2012").years()).containsExactly("2012", "2013", "2014");
        assertThat(query("/api.php/provide/vod/?year=2027-2040").years()).containsExactly("2027", "2028", "2029");
        assertThat(query("/api.php/provide/vod/?year=20").years()).containsExactly("");
        assertThat(query("/api.php/provide/vod/?year=1800-1850").years()).containsExactly("");
        assertThat(query("/api.php/provide/vod/?year=0").years()).isNull();
    }

    @Test
    void playFromFilter() throws Exception {
        LegacyRowQuery q = query("/api.php/provide/vod/?from=aaa,%20bbb,aaa,0,");
        assertThat(q.playFromContains()).containsExactly("aaa", "bbb");
        assertThat(query("/api.php/provide/vod/?from=aa").playFromContains()).isNull();
        SETTINGS.set(settings(", 'from' => 'ccc'"));
        assertThat(query("/api.php/provide/vod/?from=aaa").playFromContains()).containsExactly("ccc");
        SETTINGS.set(settings(""));
        StringBuilder many = new StringBuilder("a0");
        for (int i = 1; i <= 20; i++) {
            many.append(",a").append(i);
        }
        assertBadRequest("/api.php/provide/vod/?from=" + many);
    }

    @Test
    void textIsTrimmedAndUrlDecodedOnceMore() throws Exception {
        // 旧 input('','','trim,urldecode')：先 trim 再 urldecode，所以解码出的空格保留
        assertThat(query("/api.php/provide/vod/?wd=%2520abc%2520").nameContains()).isEqualTo(" abc ");
        assertThat(query("/api.php/provide/vod/?ids=%201,2").ids()).containsExactly("1", "2");
        assertThat(query("/api.php/provide/vod/?ids=0").ids()).isNull();
        assertBadRequest("/api.php/provide/vod/?wd=%25FF");
        assertBadRequest("/api.php/provide/vod/?wd=" + java.net.URLEncoder.encode("长".repeat(65), StandardCharsets.UTF_8));
        assertBadRequest("/api.php/provide/vod/?ids%5B%5D=1");
        assertBadRequest("/api.php/provide/vod/?ids=" + "1,".repeat(1000) + "1");
        assertThat(fetch("/api.php/provide/vod%FF").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void unavailableDataIs503WithoutDetails() throws Exception {
        when(rows.read(any())).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        MvcResult r = fetch("/api.php/provide/vod/?wd=x");
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("服务暂时不可用");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("5");
    }
}
