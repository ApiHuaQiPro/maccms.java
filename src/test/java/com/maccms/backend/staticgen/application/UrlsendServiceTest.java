package com.maccms.backend.staticgen.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.UrlsendSourceService;
import com.maccms.backend.content.infrastructure.UrlsendItemRow;
import com.maccms.backend.content.infrastructure.UrlsendSourceMapper;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * URL 推送的流程（B28，30 文档第 2 节；与 PHP 黄金的逐步对照在 {@code UrlsendBacklinkGoldenMySqlIT}）：后台一页一个请求、定时任务一次推完、
 * 推送失败即停、取不到数据时不推、不支持的地址形态不推、参数校验与"当天"的时间界限。
 */
class UrlsendServiceTest {

    @TempDir
    Path root;

    /** 2026-10-28 12:00 北京时间。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"), java.time.ZoneOffset.UTC);
    private static final long TODAY = Instant.parse("2026-10-27T16:00:00Z").getEpochSecond();

    private final List<UrlsendSourceMapper.Query> queries = new ArrayList<>();
    private final List<List<String>> pushed = new ArrayList<>();
    private final List<UrlPush.Result> results = new ArrayList<>();

    private UrlsendService service(String maccms, int rows) throws Exception {
        Files.createDirectories(root.resolve("extra"));
        Files.writeString(root.resolve("extra/maccms.php"), maccms, StandardCharsets.UTF_8);
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(root.toString()));
        UrlsendSourceMapper mapper = new UrlsendSourceMapper() {
            @Override
            public long count(UrlsendSourceMapper.Query q) {
                return rows;
            }

            @Override
            public List<UrlsendItemRow> select(UrlsendSourceMapper.Query q, long offset, int limit) {
                queries.add(q);
                List<UrlsendItemRow> out = new ArrayList<>();
                for (long id = offset + 1; id <= Math.min(rows, offset + limit); id++) {
                    out.add(new UrlsendItemRow(id, id == 2 ? "名<b>称</b>" : "名" + id, "en" + id, TODAY + id, 0));
                }
                return out;
            }
        };
        UrlPush push = (ac, site, urls) -> {
            pushed.add(urls);
            return results.isEmpty() ? new UrlPush.Result(1, "ok") : results.remove(0);
        };
        return new UrlsendService(legacy, () -> new UrlsendSourceService(mapper), () -> null, () -> push, LegacyLang.of("zh-cn"),
                CLOCK, MakeService.LEGACY_ZONE);
    }

    private static String config(String extra) {
        return "<?php\nreturn array (\n  'site' => array ('site_url' => 'www.example.test'),\n" + extra + ");";
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void adminPushesOnePageAndJumpsToTheNext() throws Exception {
        UrlsendService s = service(config(""), 5);
        UrlsendService.Output o = s.push(q("ac", "baidu", "limit", "2", "page", "1", "range", "0", "ac2", "all", "mid", "1"), "http://",
                UrlsendService.Entrance.ADMIN);
        assertThat(o.error()).isNull();
        assertThat(o.next()).containsExactly(Map.entry("ac", "baidu"), Map.entry("limit", "2"), Map.entry("page", "2"),
                Map.entry("range", "0"), Map.entry("ac2", "all"), Map.entry("mid", "1"));
        assertThat(o.html()).isEqualTo(UrlsendService.STYLE + "<br>共5条数据等待推送，分3页推送，当前第1页<br>"
                + "1、名1&nbsp;<a href=\"http://www.example.test/index.php/vod/detail/id/1.html\">"
                + "http://www.example.test/index.php/vod/detail/id/1.html</a><br>"
                + "2、名&lt;b&gt;称&lt;/b&gt;&nbsp;<a href=\"http://www.example.test/index.php/vod/detail/id/2.html\">"
                + "http://www.example.test/index.php/vod/detail/id/2.html</a><br>");
        assertThat(pushed).containsExactly(List.of("http://www.example.test/index.php/vod/detail/id/1.html",
                "http://www.example.test/index.php/vod/detail/id/2.html"));
        // 最后一页：推送完毕，没有下一页
        UrlsendService.Output last = s.push(o.next(), "https://", UrlsendService.Entrance.ADMIN);
        assertThat(last.html()).contains("当前第2页");
        last = s.push(q("ac", "baidu", "limit", "2", "page", "3", "mid", "1"), "https://", UrlsendService.Entrance.ADMIN);
        assertThat(last.next()).isNull();
        assertThat(last.html()).endsWith("https://www.example.test/index.php/vod/detail/id/5.html</a><br>数据推送完毕<br>");
    }

    @Test
    void timmingPushesAllPagesAndStopsAtTheFirstFailure() throws Exception {
        UrlsendService s = service(config(""), 5);
        UrlsendService.Output o = s.push(q("ac", "baidufast", "mid", "2", "ac2", "all", "limit", "2"), "http://",
                UrlsendService.Entrance.API);
        assertThat(o.next()).isNull();
        assertThat(pushed).hasSize(3);
        assertThat(o.html()).contains("当前第1页", "当前第2页", "当前第3页").endsWith("数据推送完毕<br>");
        assertThat(pushed.get(0).get(0)).isEqualTo("http://www.example.test/index.php/art/detail/id/1.html");

        pushed.clear();
        results.add(new UrlPush.Result(1, "ok"));
        results.add(new UrlPush.Result(102, "发生错误：<token is not valid>"));
        o = s.push(q("ac", "baidu", "mid", "3", "limit", "2"), "http://", UrlsendService.Entrance.API);
        assertThat(pushed).hasSize(2);
        assertThat(o.html()).endsWith("发生错误：&lt;token is not valid&gt;<br>").doesNotContain("数据推送完毕");
    }

    @Test
    void noDataUnsupportedFormsAndBadParametersDoNotPush() throws Exception {
        UrlsendService s = service(config(""), 0);
        UrlsendService.Output o = s.push(q("ac", "baidu", "mid", "1"), "http://", UrlsendService.Entrance.ADMIN);
        assertThat(o.html()).isEqualTo(UrlsendService.STYLE + "<br>没有获取到数据<br>");
        assertThat(pushed).isEmpty();

        assertThat(s.push(q("ac", "nope", "mid", "1"), "http://", UrlsendService.Entrance.ADMIN).error()).isEqualTo("参数错误");
        assertThat(s.push(q("mid", "1"), "http://", UrlsendService.Entrance.ADMIN).error()).isEqualTo("参数错误");

        s = service(config(""), 3);
        for (Map<String, String> bad : List.of(q("ac", "baidu", "mid", "1", "ids", "1,a"), q("ac", "baidu", "mid", "1", "limit", "10001"),
                q("ac", "baidu", "mid", "1", "page", "1000000001"))) {
            o = s.push(bad, "http://", UrlsendService.Entrance.ADMIN);
            assertThat(o.html()).as(bad.toString()).endsWith("<br>参数错误<br>");
        }
        assertThat(pushed).isEmpty();

        // 拼音编号、加密编号与演员的静态地址：新版不能生成，提示后不推
        s = service(config("  'rewrite' => array ('vod_id' => '1'),\n  'view' => array ('actor_detail' => '2'),\n"), 3);
        o = s.push(q("ac", "baidu", "mid", "1"), "http://", UrlsendService.Entrance.ADMIN);
        assertThat(o.html()).contains("vod_id 使用拼音或加密编号").doesNotContain("等待推送");
        o = s.push(q("ac", "baidu", "mid", "8"), "http://", UrlsendService.Entrance.ADMIN);
        assertThat(o.html()).contains("新系统不能生成actor的静态地址");
        assertThat(pushed).isEmpty();
    }

    @Test
    void filtersFollowTheLegacyParameters() throws Exception {
        UrlsendService s = service(config(""), 1);
        s.data(q("mid", "11", "ac2", "today", "range", "1", "ids", "3,,1"), "http://");
        s.data(q("mid", "9", "ac2", "all", "range", "01", "ids", "0", "page", "-3", "limit", "abc"), "http://");
        assertThat(queries).containsExactly(new UrlsendSourceMapper.Query(11, TODAY, true, List.of(3L, 1L)),
                new UrlsendSourceMapper.Query(9, null, true, null));
        assertThat(s.data(q("mid", "9"), "http://")).contains("http://www.example.test/index.php/role/detail/id/1.html");
    }

    @Test
    void helpers() {
        assertThat(UrlsendService.sprintf("共%s条，%s页，第%s页", "1", "2", "3")).isEqualTo("共1条，2页，第3页");
        assertThat(UrlsendService.sprintf("%s-%s", "x")).isEqualTo("x-%s");
        assertThat(UrlsendService.ids("")).isEmpty();
        assertThat(UrlsendService.ids("0")).isEmpty();
        assertThat(UrlsendService.ids("1, 2")).isNull();
        assertThat(UrlsendService.looseEqualsOne("1")).isTrue();
        assertThat(UrlsendService.looseEqualsOne("1.0")).isTrue();
        assertThat(UrlsendService.looseEqualsOne("1a")).isFalse();
        assertThat(UrlsendService.looseEqualsOne(null)).isFalse();
    }
}
