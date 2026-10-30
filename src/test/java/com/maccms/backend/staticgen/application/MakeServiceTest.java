package com.maccms.backend.staticgen.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.view.StaticSourceItem;
import com.maccms.backend.content.application.view.StaticSourceTopic;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.site.application.StaticPageSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 静态生成（25 文档第 5、6 节）：文件名与旧版一致、内容取自页面来源、分批与跳转参数、"浏览模式非静态"、文件名白名单、已生成时间回写、
 * 回滚（删除本批生成的文件）。页面来源与数据来源是替身，文件写进临时的站点根目录。
 */
class MakeServiceTest {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    /** 2026-10-26 09:30:00 PRC。 */
    static final long NOW = 1792978200L;

    @TempDir
    Path root;
    LegacyConfigService config;
    StaticSourceService source;
    StaticFiles files;
    final List<StaticPageSource.Target> rendered = new ArrayList<>();
    final Map<String, Integer> statusOf = new LinkedHashMap<>();
    StaticPageSource pages = target -> {
        rendered.add(target);
        Integer s = statusOf.get(target.toString());
        return s != null ? new StaticPageSource.Rendered(s, new byte[0])
                : new StaticPageSource.Rendered(200, ("PAGE " + target).getBytes(StandardCharsets.UTF_8));
    };

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("application/extra"));
        Files.writeString(root.resolve("index.php"), "<?php // 旧入口，不能被碰");
        config = new LegacyConfigService(new LegacyConfigProperties(root.resolve("application").toString()));
        files = new StaticFiles(new LegacySiteFiles(new LegacyConfigProperties(root.resolve("application").toString())));
        source = mock(StaticSourceService.class);
        when(source.types()).thenReturn(List.of(new StaticSourceType(1, "电影", "dianying", 0, 1),
                new StaticSourceType(6, "动作片", "dongzuo", 1, 1), new StaticSourceType(21, "资讯", "zixun", 0, 2)));
        when(source.markMade(any(), any(), anyLong())).thenReturn(1);
        writeConfig(Map.of(), Map.of(), "2");
    }

    void writeConfig(Map<String, String> view, Map<String, String> path, String makesize) throws IOException {
        Map<String, String> v = new LinkedHashMap<>(Map.of("index", "2", "vod_type", "2", "art_type", "2", "vod_detail", "2",
                "vod_play", "3", "vod_down", "0", "art_detail", "2", "topic_index", "2", "topic_detail", "2"));
        v.putAll(view);
        Map<String, String> p = new LinkedHashMap<>(Map.of("topic_index", "topic/index", "topic_detail", "topic/{id}/index",
                "vod_type", "vodtypehtml/{id}/index", "vod_detail", "vodhtml/{id}/index", "vod_play",
                "vodplayhtml/{id}/index", "vod_down", "voddownhtml/{id}/index", "art_type", "arttypehtml/{id}/index",
                "art_detail", "arthtml/{id}/index", "page_sp", "_", "suffix", "html"));
        p.putAll(path);
        Map<Object, PhpValue> all = new LinkedHashMap<>();
        all.put("app", arr(Map.of("makesize", makesize)));
        all.put("view", arr(v));
        all.put("path", arr(p));
        Files.write(root.resolve("application/extra/maccms.php"), VarExportWriter.macArr2File(new PhpArray(all)));
        Map<Object, PhpValue> player = new LinkedHashMap<>();
        player.put("yun", arr(Map.of("status", "1")));
        player.put("off", arr(Map.of("status", "0")));
        Files.write(root.resolve("application/extra/vodplayer.php"), VarExportWriter.macArr2File(new PhpArray(player)));
    }

    static PhpArray arr(Map<String, String> m) {
        Map<Object, PhpValue> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, new PhpValue.PhpString(v)));
        return new PhpArray(out);
    }

    MakeService service(StaticPageSource src) {
        return new MakeService(config, () -> source, () -> src, files, LegacyLang.of(LegacyLang.DEFAULT_CODE),
                Clock.fixed(Instant.ofEpochSecond(NOW), PRC), PRC);
    }

    MakeOutput run(String action, Map<String, String> params, MakeService.Entrance e) {
        return service(pages).run(action, params, e, "http://admin.local.test/back");
    }

    String file(String rel) throws IOException {
        return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    void indexWritesTheHomePageAtIndexHtml() throws IOException {
        MakeOutput o = run("make", Map.of("ac", "index"), MakeService.Entrance.ADMIN);
        assertThat(file("index.html")).isEqualTo("PAGE Home[wap=false]");
        assertThat(o.html()).isEqualTo(MakeService.STYLE + "<br>" + MakeService.STYLE + "<br>"
                + "<a target=\"_blank\" href=\"/index.html\"><font color=>index.html</font></a><br>");
        assertThat(o.jump().action()).isEqualTo("make/opt");
        assertThat(files.generated()).containsExactly("index.html");
        MakeOutput wap = run("index", Map.of("ac2", "wap"), MakeService.Entrance.API);
        assertThat(file("wap_index.html")).isEqualTo("PAGE Home[wap=true]");
        assertThat(wap.jump()).isNull();
    }

    @Test
    void typePagesAreBatchedByMakesizeWithTheLegacyJumpParameters() throws IOException {
        when(source.countInType("vod", 1)).thenReturn(50L);
        MakeOutput o = run("make", Map.of("ac", "type", "tab", "vod", "vodtype", "1"), MakeService.Entrance.ADMIN);
        assertThat(file("vodtypehtml/1/index.html")).isEqualTo("PAGE VodType[typeId=1, page=1]");
        assertThat(file("vodtypehtml/1/index_2.html")).isEqualTo("PAGE VodType[typeId=1, page=2]");
        assertThat(root.resolve("vodtypehtml/1/index_3.html")).doesNotExist();
        assertThat(o.html()).contains("正在生成【电影】列表页，共3页，分2次生成，当前1次")
                .contains("<a target=\"_blank\" href=\"/vodtypehtml/1/\"><font color=>第1页</font></a><br>")
                .contains("让服务器休息一会，稍后继续");
        assertThat(o.jump().action()).isEqualTo("make/make");
        assertThat(o.jump().query()).containsEntry("start", "3").containsEntry("page_count", "3")
                .containsEntry("data_count", "50").containsEntry("page_size", "20");
        // 下一批：第 3 页，然后进入下一个分类
        MakeOutput o2 = run("make", o.jump().query(), MakeService.Entrance.ADMIN);
        assertThat(file("vodtypehtml/1/index_3.html")).isEqualTo("PAGE VodType[typeId=1, page=3]");
        assertThat(o2.html()).contains("【电影】列表页生成完毕，稍后继续");
        assertThat(o2.jump().query()).containsEntry("num", "1").containsEntry("start", "1");
        MakeOutput o3 = run("make", o2.jump().query(), MakeService.Entrance.ADMIN);
        assertThat(o3.html()).contains("分类页生成完毕");
        assertThat(o3.jump().action()).isEqualTo("make/opt");
    }

    @Test
    void apiEntranceGeneratesFirstMakesizePagesOfEveryType() throws IOException {
        when(source.countInType("vod", 1)).thenReturn(50L);
        when(source.countInType("vod", 6)).thenReturn(5L);
        MakeOutput o = run("make", Map.of("ac", "type", "tab", "vod", "vodtype", "1,6"), MakeService.Entrance.API);
        assertThat(root.resolve("vodtypehtml/1/index_2.html")).exists();
        assertThat(root.resolve("vodtypehtml/1/index_3.html")).doesNotExist();
        assertThat(root.resolve("vodtypehtml/6/index.html")).exists();
        assertThat(o.html()).endsWith("定时任务完毕，本次每个分类生成2个列表页面，避免网站卡死！<br>");
        assertThat(o.jump()).isNull();
    }

    @Test
    void nonStaticViewModeStopsWithTheLegacyMessage() throws IOException {
        writeConfig(Map.of("vod_type", "0"), Map.of(), "2");
        MakeOutput o = run("make", Map.of("ac", "type", "tab", "vod", "vodtype", "1"), MakeService.Entrance.ADMIN);
        assertThat(o.html()).endsWith("浏览模式非静态，无法生成<br>");
        assertThat(o.jump()).isNull();
        assertThat(rendered).isEmpty();
    }

    @Test
    void infoWritesDetailAndEpisodePagesAndMarksMade() throws IOException {
        StaticSourceItem v = new StaticSourceItem(123, "合成 <视频>", "hecheng", 1700000000L, 6, "yun$$$off$$$yun",
                "第1集$a#第2集$b$$$x$y$$$第1集$c", "", "", "");
        when(source.count(eq("vod"), any())).thenReturn(1L);
        when(source.list(eq("vod"), any(), eq(1L), eq(2))).thenReturn(List.of(v));
        MakeOutput o = run("make", Map.of("ac", "info", "tab", "vod", "ids", "123"), MakeService.Entrance.ADMIN);
        assertThat(file("vodhtml/123/index.html")).isEqualTo("PAGE VodDetail[vodId=123]");
        // 模式 3：可用来源 sid 1（2 集）与 3（1 集）；旧版按 1..可用来源数（2）循环，sid 2 不可用，于是 sid 3 不生成（照旧）
        assertThat(file("vodplayhtml/123/index_1_1.html")).isEqualTo("PAGE VodEpisode[down=false, vodId=123, sid=1, nid=1]");
        assertThat(root.resolve("vodplayhtml/123/index_1_2.html")).exists();
        assertThat(root.resolve("vodplayhtml/123/index_3_1.html")).doesNotExist();
        assertThat(o.html()).contains("合成 &lt;视频&gt;&nbsp;").contains("<font color=>detail</font></a>&nbsp;")
                .contains("<a target=\"_blank\" href=\"/vodplayhtml/123/index_1_1.html\"><font color=>play-1-1</font></a>&nbsp;");
        verify(source).markMade(eq("vod"), eq(List.of(123L)), eq(NOW));
    }

    @Test
    void failedPagesAndForbiddenNamesAreReportedNotWritten() throws IOException {
        writeConfig(Map.of(), Map.of("vod_detail", "application/{id}/index", "topic_detail", "../{id}"), "2");
        StaticSourceItem v = new StaticSourceItem(5, "x", "x", 1700000000L, 6, "", "", "", "", "");
        when(source.count(eq("vod"), any())).thenReturn(1L);
        when(source.list(eq("vod"), any(), eq(1L), eq(2))).thenReturn(List.of(v));
        MakeOutput o = run("make", Map.of("ac", "info", "tab", "vod", "ids", "5"), MakeService.Entrance.ADMIN);
        assertThat(o.html()).contains("生成失败：application/5/index.html（文件名不在允许的范围内）");
        assertThat(root.resolve("application/5")).doesNotExist();
        when(source.topic(7)).thenReturn(new StaticSourceTopic(7, "专题", "zt"));
        MakeOutput t = run("make", Map.of("ac", "topic_info", "topic", "7"), MakeService.Entrance.ADMIN);
        assertThat(t.html()).contains("生成失败：");
        statusOf.put(new StaticPageSource.Sitemap().toString(), 404);
        MakeOutput m = run("map", Map.of(), MakeService.Entrance.ADMIN);
        assertThat(m.html()).contains("生成失败：map.html（页面状态 404）");
        assertThat(root.resolve("map.html")).doesNotExist();
        assertThat(file("index.php")).isEqualTo("<?php // 旧入口，不能被碰");
        // 自定义页名里的 ./ 照旧拒绝
        assertThat(run("make", Map.of("ac", "label", "label", "../x.html"), MakeService.Entrance.ADMIN).error())
                .isEqualTo("参数错误2");
    }

    @Test
    void nomakeStopsWhenGeneratedTimeCannotBeRecorded() {
        when(source.markMade(any(), any(), anyLong())).thenReturn(-1);
        StaticSourceItem v = new StaticSourceItem(5, "x", "x", 1700000000L, 6, "", "", "", "", "");
        when(source.count(eq("vod"), any())).thenReturn(1L);
        when(source.list(eq("vod"), any(), eq(1L), eq(2))).thenReturn(List.of(v));
        MakeOutput o = run("make", Map.of("ac", "info", "tab", "vod", "ac2", "nomake"), MakeService.Entrance.ADMIN);
        assertThat(o.html()).contains("可写数据源未开启，未记录生成时间");
        assertThat(o.jump().action()).isEqualTo("make/opt");
    }

    @Test
    void cleanupDeletesExactlyTheGeneratedFilesAndEmptyDirectories() throws IOException {
        when(source.countInType("vod", 1)).thenReturn(1L);
        Files.createDirectories(root.resolve("vodtypehtml/keep"));
        Files.writeString(root.resolve("vodtypehtml/keep/other.html"), "not ours");
        run("make", Map.of("ac", "index"), MakeService.Entrance.ADMIN);
        run("make", Map.of("ac", "type", "tab", "vod", "vodtype", "1"), MakeService.Entrance.ADMIN);
        run("make", Map.of("ac", "index"), MakeService.Entrance.ADMIN);
        assertThat(files.generated()).containsExactly("index.html", "vodtypehtml/1/index.html");
        StaticFiles.Cleanup c = files.cleanup();
        assertThat(c.deleted()).isEqualTo(2);
        assertThat(c.refused()).isEmpty();
        assertThat(root.resolve("index.html")).doesNotExist();
        assertThat(root.resolve("vodtypehtml/1")).doesNotExist();
        assertThat(file("vodtypehtml/keep/other.html")).isEqualTo("not ours");
        assertThat(root.resolve(StaticFiles.MANIFEST)).doesNotExist();
        assertThat(file("index.php")).isEqualTo("<?php // 旧入口，不能被碰");
        try (Stream<Path> s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')))
                    .containsExactlyInAnyOrder("index.php", "vodtypehtml/keep/other.html", "application/extra/maccms.php",
                            "application/extra/vodplayer.php");
        }
    }

    @Test
    void withoutSiteEveryPageFails() {
        MakeOutput o = service(null).run("make", Map.of("ac", "index"), MakeService.Entrance.ADMIN, null);
        assertThat(o.html()).contains("生成失败：index.html（前台未启用）");
        verify(source, never()).markMade(any(), any(), anyLong());
    }
}
