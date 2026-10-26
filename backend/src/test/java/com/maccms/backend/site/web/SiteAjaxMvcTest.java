package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.content.application.view.LegacyTypeDto;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 旧 ajax/data、ajax/suggest（关闭态）、ajax/desktop、qrcode 的 MockMvc 测试（B06，16 文档第 4.7–4.10 节）。数据读取是替身；
 * 与 PHP 输出的逐项对照见 AjaxGoldenMySqlIT。联想词打开态见 {@link SiteAjaxSuggestMvcTest}。
 */
@WebMvcTest(controllers = SiteAjaxController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteAjaxMvcTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    LegacyRowReadService rows;
    @MockitoBean
    ContentReadService content;

    static LegacyTypeDto type(int id, int pid, int mid, String name, String extend) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type_id", (long) id);
        m.put("type_name", name);
        m.put("type_mid", (long) mid);
        m.put("type_pid", (long) pid);
        m.put("type_extend", extend);
        return new LegacyTypeDto(new LegacyRowDto(m), id, pid, mid, true);
    }

    static LegacyRowDto vod(long id, int typeId, String pic) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vod_id", id);
        m.put("type_id", (long) typeId);
        m.put("vod_name", "视频" + id);
        m.put("vod_pic", pic);
        m.put("vod_pic_thumb", "");
        m.put("vod_time", 1700000000L);
        m.put("vod_time_add", 1690000000L);
        m.put("vod_time_hits", 1L);
        m.put("vod_time_make", 2L);
        m.put("vod_play_url", "x$http://media.example.test/1.mp4");
        m.put("vod_down_url", "");
        m.put("vod_content", "<p>正文</p>");
        return new LegacyRowDto(m);
    }

    @BeforeEach
    void setUp() {
        when(rows.types()).thenReturn(List.of(type(1, 0, 1, "电影", "{\"class\":\"动作,喜剧\",\"area\":\"\"}"),
                type(2, 1, 1, "动作片", "{}"), type(4, 0, 1, "电视剧", "not json"), type(5, 9, 1, "孤儿", "")));
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(1, List.of(vod(7, 2, "upload/vod/7.jpg"))));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(URI.create(path))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private LegacyRowQuery lastQuery() {
        ArgumentCaptor<LegacyRowQuery> q = ArgumentCaptor.forClass(LegacyRowQuery.class);
        verify(rows, Mockito.atLeastOnce()).read(q.capture());
        Mockito.clearInvocations(rows);
        return q.getValue();
    }

    @Test
    void dataRejectsUnknownModels() throws Exception {
        for (String q : List.of("", "mid=7", "mid=abc", "mid=0")) {
            MvcResult r = fetch("/index.php/ajax/data?" + q);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
            assertThat(body(r)).isEqualTo("{\"code\":1001,\"msg\":\"参数错误\"}");
        }
        verify(rows, never()).read(any());
    }

    @Test
    void dataNormalizesLimitPageAndTid() throws Exception {
        fetch("/index.php/ajax/data?mid=1&limit=15&page=21");
        LegacyRowQuery q = lastQuery();
        assertThat(q.table()).isEqualTo(LegacyTable.VOD);
        assertThat(q.columns()).isEqualTo(LegacyColumnSet.FULL);
        assertThat(q.order()).isEqualTo(LegacyRowOrder.TIME_DESC);
        assertThat(q.limit()).isEqualTo(10);
        assertThat(q.offset()).isZero();
        fetch("/index.php/ajax/data?mid=1&limit=30&page=3");
        q = lastQuery();
        assertThat(q.limit()).isEqualTo(30);
        assertThat(q.offset()).isEqualTo(60);
        // 顶级分类取直接子分类（不含本身）；没有子分类时为 IN ('')，即 0；子分类取本身；不存在的分类不筛选
        fetch("/index.php/ajax/data?mid=1&tid=1");
        assertThat(lastQuery().typeOrParentIn()).containsExactly(2);
        fetch("/index.php/ajax/data?mid=1&tid=4");
        assertThat(lastQuery().typeOrParentIn()).containsExactly(0);
        fetch("/index.php/ajax/data?mid=1&tid=2");
        assertThat(lastQuery().typeOrParentIn()).containsExactly(2);
        fetch("/index.php/ajax/data?mid=1&tid=99");
        assertThat(lastQuery().typeOrParentIn()).isNull();
        // 路径参数被查询串覆盖（mac_param_url 的 array_merge(input(), $_REQUEST)）
        fetch("/index.php/ajax/data/mid/1/limit/20?limit=30");
        assertThat(lastQuery().limit()).isEqualTo(30);
        // 演员不按 tid 筛选
        fetch("/index.php/ajax/data?mid=8&tid=1");
        assertThat(lastQuery().typeOrParentIn()).isNull();
    }

    @Test
    void dataRowsKeepTheLegacyShape() throws Exception {
        String json = body(fetch("/index.php/ajax/data?mid=1"));
        assertThat(json).startsWith("{\"code\":1,\"msg\":\"数据列表\",\"page\":1,\"pagecount\":1,\"limit\":10,\"total\":1,\"list\":[");
        assertThat(json).contains("\"vod_time\":\"2023-11-15 06:13:20\"", "\"vod_time_add\":\"2023-07-22 12:26:40\"",
                "\"detail_link\":\"\\/index.php\\/vod\\/detail\\/id\\/7.html\"", "\"vod_pic\":\"\\/upload\\/vod\\/7.jpg\"",
                "\"vod_pic_thumb\":\"\"", "\"vod_pic_slide\":\"\"");
        assertThat(json).doesNotContain("vod_time_hits", "vod_time_make", "vod_play_url", "vod_down_url");
        // type：分类整行（type_extend 解码，{} 为 []）+ 子分类的 type_1（上级带 childids）；行的 type_1 为上级
        assertThat(json).contains("\"type\":{\"type_id\":2,\"type_name\":\"动作片\",\"type_mid\":1,\"type_pid\":1,"
                + "\"type_extend\":[],\"type_1\":{\"type_id\":1,\"type_name\":\"电影\",\"type_mid\":1,\"type_pid\":0,"
                + "\"type_extend\":{\"class\":\"动作,喜剧\",\"area\":\"\"},\"childids\":\"2\"}}");
        assertThat(json).contains("\"type_1\":{\"type_id\":1,\"type_name\":\"电影\"");
        assertThat(json).doesNotContain("group");
    }

    @Test
    void topicListIsKeyedAndRoleDataIsTheVisibleVideo() throws Exception {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("topic_id", 3L);
        t.put("topic_time", 1L);
        t.put("topic_time_add", 1L);
        t.put("topic_pic", "mac://img.example.test/t.jpg");
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(1, List.of(new LegacyRowDto(t))));
        String json = body(fetch("/index.php/ajax/data?mid=3"));
        assertThat(json).contains("\"list\":{\"3\":{\"topic_id\":3,").contains("\"topic_pic\":\"http:\\/\\/img.example.test\\/t.jpg\"");
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(0, List.of()));
        assertThat(body(fetch("/index.php/ajax/data?mid=3"))).endsWith("\"list\":[]}");

        Map<String, Object> role = new LinkedHashMap<>();
        role.put("role_id", 5L);
        role.put("role_rid", 7L);
        role.put("role_time", 1L);
        role.put("role_time_add", 1L);
        role.put("role_pic", "");
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(1, List.of(new LegacyRowDto(role))));
        when(rows.visibleVideos(any(), any())).thenReturn(Map.of(7L, vod(7, 2, "")));
        json = body(fetch("/index.php/ajax/data?mid=9"));
        assertThat(json).contains("\"data\":{\"vod_id\":7,").contains("\"vod_time\":1700000000")
                .doesNotContain("vod_play_url");
        when(rows.visibleVideos(any(), any())).thenReturn(Map.of());
        assertThat(body(fetch("/index.php/ajax/data?mid=9"))).contains("\"data\":null");
    }

    @Test
    void suggestIsClosedUnlessTheSearchSwitchesAreOn() throws Exception {
        when(content.searchEnabled()).thenReturn(true);
        MvcResult r = fetch("/index.php/ajax/suggest?mid=1&wd=a");
        assertThat(body(r)).isEqualTo("{\"code\":999,\"msg\":\"联想搜索功能关闭中\"}");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        verify(rows, never()).read(any());
    }

    @Test
    void desktopShortcutIsByteCompatibleAndRejectsInjection() throws Exception {
        MvcResult r = fetch("/index.php/ajax/desktop");
        assertThat(r.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(r.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"___.url\"; filename*=UTF-8''%E5%90%88%E6%88%90%E7%AB%99.url");
        assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(("[InternetShortcut]\r\n        URL=http://maccms.local.test\r\n"
                + "        IDList=\r\n        IconIndex=1\r\n        [{000214A0-0000-0000-C000-000000000046}]\r\n"
                + "        Prop3=19,2").getBytes(StandardCharsets.UTF_8));
        r = fetch("/index.php/ajax/desktop?name=a%22b&url=x.test%2F%3Fa%3D1%26b%3D2");
        assertThat(r.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"a&quot;b.url\"; filename*=UTF-8''a&quot%3Bb.url");
        assertThat(body(r)).contains("URL=http://x.test/?a=1&amp;b=2\r\n");
        for (String q : List.of("name=a&url=x%0D%0AIconFile%3D%5C%5Cevil%5Cshare", "name=a%0Ab&url=x", "name=a&url=x%09y",
                "name=a&url=%25E5", "name=a&url=%25FF")) {
            MvcResult bad = fetch("/index.php/ajax/desktop?" + q);
            assertThat(bad.getResponse().getStatus()).as(q).isEqualTo(400);
            assertThat(bad.getResponse().getHeader("Content-Disposition")).isNull();
        }
    }

    @Test
    void qrcodeGeneratesOnlyForLegacyValidUrls() throws Exception {
        MvcResult r = fetch("/index.php/qrcode/index.html?url=http%3A%2F%2Fmaccms.local.test%2F");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("max-age=86400, public");
        byte[] png = r.getResponse().getContentAsByteArray();
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        for (String q : List.of("", "url=", "url=not%20a%20url", "url=javascript%3Aalert(1)", "url=%2F%2Fx.test%2F")) {
            MvcResult bad = fetch("/index.php/qrcode/index?" + q);
            assertThat(bad.getResponse().getStatus()).as(q).isEqualTo(200);
            assertThat(bad.getResponse().getContentAsByteArray()).as(q).isEmpty();
            assertThat(bad.getResponse().getContentType()).isEqualTo("text/plain;charset=UTF-8");
        }
        // 路径参数优先于查询串（ThinkPHP input()）
        assertThat(fetch("/index.php/qrcode/index/url/x?url=http%3A%2F%2Fa.test%2F").getResponse()
                .getContentAsByteArray()).isEmpty();
    }

    /** {@code ajax/hits|digg|score|referer|verify_check} 自 B11 起实现（InteractionGoldenMySqlIT 对照黄金），不再列入。 */
    @Test
    void otherAjaxActionsAndRewriteFormsAre404() throws Exception {
        for (String p : List.of("/index.php/ajax/pwd", "/ajax/data?mid=1", "/qrcode/index?url=x",
                "/index.php/other/data")) {
            assertThat(fetch(p).getResponse().getStatus()).as(p).isEqualTo(404);
        }
    }
}
