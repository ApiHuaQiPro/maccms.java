package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.collect.infrastructure.DirectoryProvideSourceFiles;
import com.maccms.backend.collect.infrastructure.FileCollectBreakpointStore;
import com.maccms.backend.collect.infrastructure.FileProvidePreviewStore;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 后台入库与断点续采（B19 增量 3，27 文档第 12 节）的编排：取页与筛选（{@code t}、{@code wd}、{@code ids}、{@code h}）、断点的写下与删除、
 * 下一页/完成/{@code cjsel} 的跳转参数、"参数错误"与入库排队超时的行、同一页重试与重试上限、没有载入时不记断点。入库本身用替身
 * （规则由 {@code VodIngestTest}、{@code KindIngestTest} 与 {@code ReceiveGoldenMySqlIT} 覆盖），所以本测试不连库。
 */
class CollectRunServiceTest {

    private static final String FLAG = CollectBindings.flag("http://provide.invalid/api.php/provide/vod/");
    private static final UnaryOperator<String> LANG = k -> k.equals("param_err") ? "参数错误" : k;
    /** 2026-10-26 12:00:00 北京时间。 */
    private static final Instant NOW = Instant.parse("2026-10-26T04:00:00Z");

    @TempDir
    Path tmp;

    private Path root;
    private Path cacheDir;
    private FileCollectBreakpointStore breaks;
    private CollectPreviewService preview;
    private FakeIngest ingest;
    private CollectRunService runs;

    /** 记下调用的替身：名称以"坏"开头的行"参数错误"，在 {@code busy} 里的名称排队超时（每次调用消耗一次）。 */
    private static final class FakeIngest implements CollectRunService.Ingest {
        final List<String> calls = new ArrayList<>();
        final List<String> busy = new ArrayList<>();
        Object timespan = "";
        VodIngest.Param lastParam;

        private VodIngest.Outcome one(String kind, Object row, String nameField) {
            String name = ProvideValues.str(ProvideValues.get(row, nameField));
            calls.add(kind + ":" + name);
            if (name.startsWith("坏")) {
                throw new VodIngest.Rejected("x");
            }
            if (busy.remove(name)) {
                throw new CollectIngestService.Busy();
            }
            return new VodIngest.Outcome(true, "新加入库，成功ok。", name);
        }

        @Override
        public VodIngest.Outcome vod(Object row, VodIngest.Param param, UnaryOperator<String> lang) {
            lastParam = param;
            return one("vod", row, "vod_name");
        }

        @Override
        public VodIngest.Outcome kind(KindIngest.Kind kind, Object row, UnaryOperator<String> lang) {
            return one(kind.prefix(), row, kind.prefix() + "_name");
        }

        @Override
        public Map<Object, Object> maccms() {
            Map<Object, Object> app = new LinkedHashMap<>();
            app.put("collect_timespan", timespan);
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put("app", app);
            return m;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("application"));
        Files.createDirectories(root.resolve("extra"));
        Files.writeString(root.resolve("extra/maccms.php"), "<?php\nreturn array (\n);", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("extra/bind.php"), "<?php\nreturn array (\n  '" + FLAG + "_1' => 5,\n);", StandardCharsets.UTF_8);
        cacheDir = tmp.resolve("cache");
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(root.toString()));
        TypeNames types = id -> Optional.empty();
        MutableClock clock = new MutableClock(NOW.getEpochSecond());
        preview = new CollectPreviewService(new FileProvidePreviewStore(cacheDir), new DirectoryProvideSourceFiles(null),
                new CollectBindService(legacy, types), types, legacy, clock, Duration.ofHours(24), 10_240_000, 200);
        breaks = new FileCollectBreakpointStore(cacheDir);
        ingest = new FakeIngest();
        runs = new CollectRunService(preview, ingest, breaks, clock);
    }

    private static CollectPreviewService.Source vod() {
        return new CollectPreviewService.Source(FLAG, "1", "2", "");
    }

    /** {@code n} 行视频：第 i 行远程分类 1（第 3 的倍数为 3），时间按 {@code times} 循环取。 */
    private void loadVod(int n, String... names) {
        StringBuilder sb = new StringBuilder("{\"page\":1,\"pagecount\":1,\"list\":[");
        for (int i = 1; i <= n; i++) {
            String name = i <= names.length ? names[i - 1] : "片" + i;
            sb.append(i > 1 ? "," : "").append("{\"vod_id\":").append(i).append(",\"type_id\":").append(i % 3 == 0 ? 3 : 1)
                    .append(",\"vod_name\":\"").append(name).append("\",\"vod_time\":\"").append(i % 2 == 0 ? "2026-10-26 08:00:00" : "2026-10-20 08:00:00")
                    .append("\"}");
        }
        sb.append("]}");
        assertThat(preview.load(vod(), "vod.json", sb.toString().getBytes(StandardCharsets.UTF_8), LANG).ok()).isTrue();
    }

    /** 旧"采集所有"按钮的参数（{@code page}、{@code limit} 是没替换的占位符）。 */
    private static Map<String, String> param(String ac, String... kv) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ac", ac);
        p.put("cjflag", FLAG);
        p.put("cjurl", "http://provide.invalid/api.php/provide/vod/");
        p.put("h", "");
        p.put("t", "");
        p.put("ids", "");
        p.put("wd", "");
        p.put("type", "2");
        p.put("mid", "1");
        p.put("opt", "0");
        p.put("sync_pic_opt", "0");
        p.put("filter", "0");
        p.put("filter_from", "");
        p.put("param", "");
        p.put("page", "{page}");
        p.put("limit", "{limit}");
        for (int i = 0; i < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    private CollectRunService.RunPage run(Map<String, String> p) {
        return runs.run(vod(), p, "/admin/collect/api?page=" + p.get("page"), LANG).orElseThrow();
    }

    @Test
    void walksPagesWritesBreakpointAndRemovesItWhenDone() {
        loadVod(45);
        CollectRunService.RunPage p1 = run(param("cjall"));
        assertThat(p1.page()).isEqualTo(1);
        assertThat(p1.pageCount()).isEqualTo(3);
        assertThat(p1.pageNow()).as("(int)'{page}'").isZero();
        assertThat(p1.lines()).hasSize(20);
        assertThat(p1.lines().get(0)).isEqualTo(new CollectRunService.Line(1, "片1", true, "新加入库，成功ok。"));
        assertThat(p1.end()).isEqualTo(CollectRunService.End.NEXT);
        assertThat(p1.jump()).containsEntry("page", "2").containsEntry("ac", "cjall").containsEntry("limit", "{limit}");
        assertThat(p1.seconds()).isEqualTo(3);
        assertThat(breaks.get("vod")).contains("/admin/collect/api?page={page}");
        assertThat(runs.breakpoints()).containsEntry(ProvideKind.VOD, true).containsEntry(ProvideKind.ART, false);
        assertThat(ingest.lastParam).isEqualTo(new VodIngest.Param("0", "0", "", "0"));

        CollectRunService.RunPage p2 = run(p1.jump());
        assertThat(p2.pageNow()).isEqualTo(2);
        assertThat(p2.lines().get(0).name()).isEqualTo("片21");
        assertThat(breaks.get("vod")).contains("/admin/collect/api?page=2");

        CollectRunService.RunPage p3 = run(p2.jump());
        assertThat(p3.lines()).hasSize(5);
        assertThat(p3.end()).isEqualTo(CollectRunService.End.DONE);
        assertThat(p3.jump()).containsEntry("ac", "list").doesNotContainKeys("page", "ids", "retry");
        assertThat(breaks.get("vod")).isEmpty();
        assertThat(runs.breakpoints()).doesNotContainValue(true);
        assertThat(ingest.calls).hasSize(45);
    }

    @Test
    void resumingFromTheBreakpointRerunsOnlyThatPage() {
        loadVod(45);
        CollectRunService.RunPage p1 = run(param("cjall"));
        run(p1.jump());
        // 第 2 页做完后中断：断点仍指向第 2 页（旧版在页开始时写下），从断点续采只把这一页再入库一遍
        assertThat(runs.breakpoint("vod")).contains("/admin/collect/api?page=2");
        assertThat(runs.breakpoint("comment")).isEmpty();
        assertThat(runs.breakpoint("../x")).isEmpty();
        ingest.calls.clear();
        CollectRunService.RunPage again = run(param("cjall", "page", "2"));
        assertThat(ingest.calls).hasSize(20).first().isEqualTo("vod:片21");
        assertThat(again.jump()).containsEntry("page", "3");
    }

    @Test
    void rejectedRowsAreReportedAndDoNotStopThePage() {
        loadVod(3, "片1", "坏行", "片3");
        CollectRunService.RunPage r = run(param("cjall"));
        assertThat(r.lines()).extracting(CollectRunService.Line::green).containsExactly(true, false, true);
        assertThat(r.lines().get(1)).isEqualTo(new CollectRunService.Line(2, "坏行", false, "参数错误"));
        assertThat(r.end()).isEqualTo(CollectRunService.End.DONE);
    }

    @Test
    void busyRowsRetryTheSamePageUpToTheLimit() {
        loadVod(25);
        ingest.busy.addAll(List.of("片2", "片2", "片2", "片2"));
        CollectRunService.RunPage r = run(param("cjall"));
        assertThat(r.busy()).isEqualTo(1);
        assertThat(r.lines().get(1)).isEqualTo(new CollectRunService.Line(2, "片2", false, CollectRunService.BUSY));
        assertThat(r.end()).isEqualTo(CollectRunService.End.RETRY);
        assertThat(r.jump()).containsEntry("page", "1").containsEntry("retry", "1");
        assertThat(breaks.get("vod")).isPresent();
        for (int i = 2; i <= CollectRunService.MAX_RETRY; i++) {
            r = run(r.jump());
            assertThat(r.end()).isEqualTo(CollectRunService.End.RETRY);
            assertThat(r.jump()).containsEntry("retry", Integer.toString(i));
        }
        // 第 3 次重试仍超时：不再重来，翻到下一页（这一行留在输出里，可从断点或"采集选中"再入库）
        r = run(r.jump());
        assertThat(r.busy()).isEqualTo(1);
        assertThat(r.end()).isEqualTo(CollectRunService.End.NEXT);
        assertThat(r.jump()).containsEntry("page", "2").doesNotContainKey("retry");
        // 重试成功后照常翻页
        ingest.busy.add("片21");
        r = run(r.jump());
        assertThat(r.end()).isEqualTo(CollectRunService.End.RETRY);
        r = run(r.jump());
        assertThat(r.busy()).isZero();
        assertThat(r.end()).isEqualTo(CollectRunService.End.DONE);
        assertThat(breaks.get("vod")).isEmpty();
    }

    @Test
    void selectedIdsAreIngestedTogetherAndGoBackWithoutBreakpoint() {
        loadVod(45);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            ids.add(Integer.toString(i + 10));
        }
        CollectRunService.RunPage r = run(param("cjsel", "ids", String.join(",", ids)));
        assertThat(r.lines()).as("选中的 30 行一次入库，不按 limit 分页").hasSize(30);
        assertThat(r.lines().get(0).name()).isEqualTo("片11");
        assertThat(r.end()).isEqualTo(CollectRunService.End.SELECTED_DONE);
        assertThat(r.jump()).containsEntry("ac", "list").doesNotContainKey("ids").containsEntry("page", "{page}");
        assertThat(breaks.get("vod")).isEmpty();
    }

    @Test
    void filtersByRemoteTypeNameAndHours() {
        loadVod(12);
        assertThat(run(param("cjall", "t", "3")).lines()).extracting(CollectRunService.Line::name).containsExactly("片3", "片6", "片9", "片12");
        assertThat(run(param("cjall", "wd", "片1")).lines()).extracting(CollectRunService.Line::name).containsExactly("片1", "片10", "片11",
                "片12");
        // 采集当天（h=24）：只取偶数行（2026-10-26 08:00，6 天前的奇数行不入选）
        assertThat(run(param("cj", "h", "24")).lines()).extracting(CollectRunService.Line::name)
                .containsExactly("片2", "片4", "片6", "片8", "片10", "片12");
        // 视频的 h 为空时取 rday（旧 vod_xml）
        assertThat(run(param("cj", "rday", "24")).lines()).hasSize(6);
    }

    @Test
    void hoursWindowFollowsProvide() {
        // 视频：大于 现在-h 小时；其他种类：大于那一天的零点；都小于明天零点；时间取不出时不入选
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, "2026-10-26 11:00:01", 1, NOW)).isTrue();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, "2026-10-26 11:00:00", 1, NOW)).isFalse();
        assertThat(CollectPreviewService.withinHours(ProvideKind.ART, "2026-10-26 00:00:01", 1, NOW)).isTrue();
        assertThat(CollectPreviewService.withinHours(ProvideKind.ART, "2026-10-26 00:00:00", 1, NOW)).isFalse();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, "2026-10-27 00:00:00", 24, NOW)).isFalse();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, Long.toString(NOW.getEpochSecond() - 60), 1, NOW)).isTrue();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, "昨天", 24, NOW)).isFalse();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, null, 24, NOW)).isFalse();
        assertThat(CollectPreviewService.withinHours(ProvideKind.VOD, "2026-02-30 10:00:00", 24 * 365, NOW)).isFalse();
    }

    @Test
    void otherKindsUseTheirIngestAndBreakpoint() {
        CollectPreviewService.Source art = new CollectPreviewService.Source(FLAG, "2", "", "");
        assertThat(preview.load(art, "art.json", "{\"list\":[{\"art_id\":1,\"type_id\":1,\"art_name\":\"文甲\"}]}"
                .getBytes(StandardCharsets.UTF_8), LANG).ok()).isTrue();
        Map<String, String> p = param("cjall", "mid", "2");
        CollectRunService.RunPage r = runs.run(art, p, "/x?mid=2", LANG).orElseThrow();
        assertThat(r.kind()).isEqualTo(ProvideKind.ART);
        assertThat(r.pageNow()).isZero();
        assertThat(ingest.calls).containsExactly("art:文甲");
        assertThat(r.end()).isEqualTo(CollectRunService.End.DONE);
        assertThat(breaks.get("art")).isEmpty();
    }

    @Test
    void nothingLoadedMeansNoBreakpointAndNoIngest() {
        assertThat(runs.run(vod(), param("cjall"), "/x", LANG)).isEmpty();
        assertThat(runs.run(new CollectPreviewService.Source(FLAG, "7", "", ""), param("cjall"), "/x", LANG)).isEmpty();
        assertThat(breaks.get("vod")).isEmpty();
        assertThat(ingest.calls).isEmpty();
    }

    @Test
    void pageBeyondTheEndIsEmptyAndFinishes() {
        loadVod(5);
        CollectRunService.RunPage r = run(param("cjall", "page", "9"));
        assertThat(r.lines()).isEmpty();
        assertThat(r.end()).isEqualTo(CollectRunService.End.DONE);
        assertThat(ingest.calls).isEmpty();
    }

    @Test
    void timespanFollowsLegacyEmptyRule() {
        ingest.timespan = "0";
        assertThat(CollectRunService.seconds(ingest.maccms())).isEqualTo(3);
        ingest.timespan = "5";
        assertThat(CollectRunService.seconds(ingest.maccms())).isEqualTo(5);
        ingest.timespan = "-2";
        assertThat(CollectRunService.seconds(ingest.maccms())).isZero();
        assertThat(CollectRunService.seconds(Map.of())).isEqualTo(3);
    }

    @Test
    void breakpointStoreKeepsItsOwnFilesOnly() throws Exception {
        breaks.put("vod", "/admin/collect/api?ac=cjall&page=2");
        assertThat(breaks.get("vod")).contains("/admin/collect/api?ac=cjall&page=2");
        assertThat(breaks.dir()).isEqualTo(cacheDir.toAbsolutePath().normalize().resolve("breakpoints"));
        try (Stream<Path> s = Files.list(breaks.dir())) {
            assertThat(s.map(x -> x.getFileName().toString())).containsExactly("collect_break_vod.url");
        }
        // 预览缓存的清理不碰断点
        preview.clearSite(FLAG);
        assertThat(breaks.get("vod")).isPresent();
        assertThat(breaks.remove("vod")).isTrue();
        assertThat(breaks.remove("vod")).isFalse();
        assertThatThrownBy(() -> breaks.put("../vod", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> breaks.get("VOD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> breaks.put("vod", "x".repeat(20_000))).isInstanceOf(IllegalArgumentException.class);
    }
}
