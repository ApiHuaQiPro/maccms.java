package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.collect.infrastructure.DirectoryProvideSourceFiles;
import com.maccms.backend.collect.infrastructure.FileProvidePreviewStore;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 预览服务（B18，26 文档第 4、5 节）在临时目录上的行为（不连库、不联网——服务本身不持有任何数据源或网络客户端）：载入（上传与本地目录）、
 * 超大文件与非法文件名的拒绝、预览时按当前绑定换算与入库预检、本地筛选与分页、缓存只写在缓存目录、过期与清除、损坏的缓存项、并发载入。
 */
class CollectPreviewServiceTest {

    private static final String FLAG = CollectBindings.flag("http://provide.invalid/api.php/provide/vod/");
    private static final UnaryOperator<String> LANG = k -> switch (k) {
        case "unknown_type" -> "未知分类";
        case "model/collect/json_err" -> "JSON格式不正确";
        default -> k;
    };

    @TempDir
    Path tmp;

    private Path root;
    private Path cacheDir;
    private Path sourceDir;
    private MutableClock clock;
    private FileProvidePreviewStore store;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("application"));
        Files.createDirectories(root.resolve("extra"));
        Files.writeString(root.resolve("extra/maccms.php"), "<?php\nreturn array (\n  'collect' => \n  array (\n    'vod' => \n    array (\n"
                + "      'filter' => '广告,,0',\n    ),\n  ),\n);", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("extra/bind.php"), "<?php\nreturn array (\n  '" + FLAG + "_1' => 5,\n);", StandardCharsets.UTF_8);
        cacheDir = tmp.resolve("cache");
        sourceDir = Files.createDirectories(tmp.resolve("source"));
        clock = new MutableClock(Instant.parse("2026-10-26T04:00:00Z").getEpochSecond());
        store = new FileProvidePreviewStore(cacheDir);
    }

    private CollectPreviewService service(long maxBytes, int maxEntries) {
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(root.toString()));
        TypeNames types = id -> id == 5 ? Optional.of("动作片") : Optional.empty();
        return new CollectPreviewService(store, new DirectoryProvideSourceFiles(sourceDir), new CollectBindService(legacy, types), types,
                legacy, clock, Duration.ofHours(24), maxBytes, maxEntries);
    }

    private CollectPreviewService service() {
        return service(10_240_000, 200);
    }

    private static CollectPreviewService.Source vod() {
        return new CollectPreviewService.Source(FLAG, "1", "2", "");
    }

    private static byte[] vodJson(int rows) {
        StringBuilder sb = new StringBuilder("{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":" + rows + ",\"list\":[");
        for (int i = 1; i <= rows; i++) {
            if (i > 1) {
                sb.append(',');
            }
            int type = i % 3 == 0 ? 3 : 1;
            String name = i == 2 ? "乙 广告" : "片" + i;
            sb.append("{\"vod_id\":").append(i).append(",\"type_id\":").append(type).append(",\"type_name\":\"t").append(type)
                    .append("\",\"vod_name\":\"").append(name).append("\",\"vod_time\":\"2026-10-2").append(i % 2 == 0 ? "6" : "5")
                    .append(" 10:00:00\"}");
        }
        sb.append("],\"class\":[{\"type_id\":1,\"type_name\":\"动作\"},{\"type_id\":3,\"type_name\":\"未绑\"}]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void loadThenPreviewAppliesCurrentBindingsAndPrechecks() throws Exception {
        CollectPreviewService s = service();
        CollectPreviewService.LoadOutcome r = s.load(vod(), "C:\\up\\vod-1.json", vodJson(5), LANG);
        assertThat(r.ok()).isTrue();
        assertThat(r.rows()).isEqualTo(5);

        CollectPreviewService.Preview p = s.preview(vod(), "", "", "", "", LANG).orElseThrow();
        assertThat(p.sourceName()).isEqualTo("vod-1.json");
        assertThat(p.total()).isEqualTo(5);
        assertThat(p.types()).containsExactly(new CollectPreviewService.TypeBinding("1", "动作", true, 5, "动作片"),
                new CollectPreviewService.TypeBinding("3", "未绑", false, 0, ""));
        assertThat(p.rows()).extracting(CollectPreviewService.PreviewRow::check)
                .containsExactly("", "model/collect/name_in_filter_err", "model/collect/type_err", "", "");
        assertThat(p.rows().get(0).s("type_id")).isEqualTo("5");
        assertThat(p.rows().get(2).s("type_id")).isEqualTo("0");
        assertThat(((Map<?, ?>) p.result().get("page")).get("url")).isEqualTo("vod-1.json");

        // 改绑定后不必重新载入：预览按当时的 bind.php 换算；绑定到不存在的分类时显示"未知分类"
        Files.writeString(root.resolve("extra/bind.php"), "<?php\nreturn array (\n  '" + FLAG + "_1' => 5,\n  '" + FLAG + "_3' => 9,\n);",
                StandardCharsets.UTF_8);
        CollectPreviewService.Preview p2 = s.preview(vod(), "", "", "", "", LANG).orElseThrow();
        assertThat(p2.types().get(1)).isEqualTo(new CollectPreviewService.TypeBinding("3", "未绑", true, 9, "未知分类"));
        assertThat(p2.rows().get(2).check()).isEmpty();
        assertThat(p2.rows().get(2).s("type_id")).isEqualTo("9");
    }

    @Test
    void localFilterAndPaging() {
        CollectPreviewService s = service();
        s.load(vod(), "a.json", vodJson(45), LANG);
        CollectPreviewService.Preview all = s.preview(vod(), null, null, null, null, LANG).orElseThrow();
        assertThat(all.total()).isEqualTo(45);
        assertThat(all.limit()).isEqualTo(CollectPreviewService.DEFAULT_LIMIT);
        assertThat(all.rows()).hasSize(20);
        CollectPreviewService.Preview last = s.preview(vod(), "", "", "3", "20", LANG).orElseThrow();
        assertThat(last.page()).isEqualTo(3);
        assertThat(last.rows()).hasSize(5);
        assertThat(last.rows().get(0).s("vod_id")).isEqualTo("41");
        // 页码越界取最后一页；limit 不合法取默认
        assertThat(s.preview(vod(), "", "", "99", "abc", LANG).orElseThrow().page()).isEqualTo(3);
        // t：远程分类编号；wd：名称包含
        CollectPreviewService.Preview t3 = s.preview(vod(), "3", "", "1", "100", LANG).orElseThrow();
        assertThat(t3.total()).isEqualTo(15);
        assertThat(t3.rows()).allSatisfy(r -> assertThat(Long.parseLong(r.s("vod_id")) % 3).isZero());
        assertThat(s.preview(vod(), "", "广告", "1", "", LANG).orElseThrow().total()).isEqualTo(1);
        assertThat(s.preview(vod(), "", "片4", "1", "", LANG).orElseThrow().rows()).extracting(r -> r.s("vod_name"))
                .containsExactly("片4", "片40", "片41", "片42", "片43", "片44", "片45");
    }

    @Test
    void failuresAreNotCachedAndOversizedFilesAreRejected() throws Exception {
        CollectPreviewService s = service(1000, 200);
        CollectPreviewService.LoadOutcome bad = s.load(vod(), "x.json", "{\"list\":".getBytes(StandardCharsets.UTF_8), LANG);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.message()).startsWith("JSON格式不正确");
        CollectPreviewService.LoadOutcome big = s.load(vod(), "big.json", new byte[1001], LANG);
        assertThat(big.ok()).isFalse();
        assertThat(big.message()).contains("大小上限");
        assertThat(s.load(vod(), null, null, LANG).ok()).isFalse();
        assertThat(s.load(new CollectPreviewService.Source(FLAG, "3", "", ""), "a.json", vodJson(1), LANG).ok()).isFalse();
        assertThat(s.preview(vod(), "", "", "", "", LANG)).isEmpty();
        assertThat(cacheFiles()).isEmpty();
        // 本地目录里超过上限的文件不读
        Files.write(sourceDir.resolve("big.json"), new byte[1001]);
        assertThat(s.loadLocal(vod(), "big.json", LANG).message()).contains("大小上限");
    }

    @Test
    void localDirectoryOnlyServesPlainFilesDirectlyInside() throws Exception {
        CollectPreviewService s = service();
        Files.write(sourceDir.resolve("b.json"), vodJson(2));
        Files.write(sourceDir.resolve("a.XML"), "<rss/>".getBytes(StandardCharsets.UTF_8));
        Files.write(sourceDir.resolve("notes.md"), new byte[] {1});
        Files.write(sourceDir.resolve(".hidden.json"), vodJson(1));
        Files.createDirectories(sourceDir.resolve("sub.json"));
        Files.write(tmp.resolve("outside.json"), vodJson(3));
        assertThat(s.localFiles()).extracting(CollectPreviewService.LocalFile::name).containsExactly("a.XML", "b.json");
        assertThat(s.loadLocal(vod(), "b.json", LANG).rows()).isEqualTo(2);
        for (String name : List.of("../outside.json", "..\\outside.json", tmp.resolve("outside.json").toString(), "sub.json",
                ".hidden.json", "notes.md", "missing.json", "", "b.json/", "b.json\u0000")) {
            assertThat(s.loadLocal(vod(), name, LANG).ok()).as(name).isFalse();
        }
        // 指向目录外的符号链接（Windows 上没有权限建链接时跳过这一段）
        Path link = sourceDir.resolve("link.json");
        try {
            Files.createSymbolicLink(link, tmp.resolve("outside.json"));
        } catch (Exception e) {
            link = null;
        }
        if (link != null) {
            assertThat(s.localFiles()).extracting(CollectPreviewService.LocalFile::name).doesNotContain("link.json");
            assertThat(s.loadLocal(vod(), "link.json", LANG).ok()).isFalse();
        }
        // 未配置目录
        CollectPreviewService none = new CollectPreviewService(store, new DirectoryProvideSourceFiles(null), null, null, null, clock,
                Duration.ofHours(1), 100, 10);
        assertThat(none.localDirectoryConfigured()).isFalse();
        assertThat(none.localFiles()).isEmpty();
        assertThat(none.loadLocal(vod(), "b.json", LANG).ok()).isFalse();
    }

    @Test
    void cacheLivesOnlyInTheCacheDirectoryAndExpires() throws Exception {
        CollectPreviewService s = service();
        s.load(vod(), "a.json", vodJson(3), LANG);
        CollectPreviewService.Source art = new CollectPreviewService.Source(FLAG, "2", "", "");
        s.load(art, "art.json", "{\"list\":[{\"art_id\":1,\"type_id\":1,\"art_name\":\"文\"}]}".getBytes(StandardCharsets.UTF_8), LANG);
        CollectPreviewService.Source other = new CollectPreviewService.Source(CollectBindings.flag("http://other.invalid/"), "1", "2", "");
        s.load(other, "o.json", vodJson(1), LANG);
        assertThat(cacheFiles()).hasSize(3).allSatisfy(f -> assertThat(f.getFileName().toString()).matches("[0-9a-f]{32}-[0-9a-f]{40}\\.json"));
        // 旧站目录与本地文件目录没有被写
        try (Stream<Path> w = Files.walk(tmp)) {
            assertThat(w.filter(Files::isRegularFile).filter(f -> !f.startsWith(cacheDir)).map(f -> tmp.relativize(f).toString().replace('\\', '/')))
                    .containsExactlyInAnyOrder("application/extra/maccms.php", "application/extra/bind.php");
        }
        // 同一来源不同 param 是不同的项；param 不同读不到
        assertThat(s.preview(new CollectPreviewService.Source(FLAG, "1", "2", "Y3Q9MQ=="), "", "", "", "", LANG)).isEmpty();

        // 清除一个来源
        assertThat(s.clear(art)).isTrue();
        assertThat(s.preview(art, "", "", "", "", LANG)).isEmpty();
        assertThat(s.clear(art)).isFalse();
        // 清除一个资源站的全部
        assertThat(s.clearSite(other.cjflag())).isEqualTo(1);
        assertThat(cacheFiles()).hasSize(1);

        // 过期：24 小时后读不到，并被删除
        Path f = cacheFiles().get(0);
        Files.setLastModifiedTime(f, FileTime.from(clock.instant().minus(Duration.ofHours(25))));
        assertThat(s.preview(vod(), "", "", "", "", LANG)).isEmpty();
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void purgeKeepsAtMostMaxEntriesAndDropsExpired() throws Exception {
        CollectPreviewService s = service(10_240_000, 3);
        for (int i = 0; i < 5; i++) {
            CollectPreviewService.Source src = new CollectPreviewService.Source(CollectBindings.flag("http://s" + i + ".invalid/"), "1", "2", "");
            s.load(src, "a.json", vodJson(1), LANG);
            Files.setLastModifiedTime(cacheFiles().stream().filter(p -> p.getFileName().toString().startsWith(src.cjflag())).findFirst()
                    .orElseThrow(), FileTime.from(clock.instant().minusSeconds(100 - i)));
        }
        assertThat(cacheFiles()).hasSize(3);
        // 目录里不是缓存项的文件不碰
        Files.writeString(cacheDir.resolve("README.txt"), "x");
        Files.setLastModifiedTime(cacheFiles().get(0), FileTime.from(clock.instant().minus(Duration.ofDays(2))));
        s.clearSite("nothing");
        assertThat(cacheFiles()).hasSize(2);
        assertThat(cacheDir.resolve("README.txt")).exists();
    }

    @Test
    void corruptedOrForeignCacheEntriesAreIgnored() throws Exception {
        CollectPreviewService s = service();
        s.load(vod(), "a.json", vodJson(2), LANG);
        Path f = cacheFiles().get(0);
        Files.writeString(f, "{not json");
        assertThat(s.preview(vod(), "", "", "", "", LANG)).isEmpty();
        assertThat(f).doesNotExist();
        s.load(vod(), "a.json", vodJson(2), LANG);
        Files.writeString(cacheFiles().get(0), "{\"v\":1,\"cjflag\":\"other\",\"kind\":\"vod\",\"type\":\"2\",\"param\":\"\"}");
        assertThat(s.preview(vod(), "", "", "", "", LANG)).isEmpty();
    }

    @Test
    void concurrentLoadsLeaveOneCompleteEntry() throws Exception {
        CollectPreviewService s = service();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<CollectPreviewService.LoadOutcome>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = 1 + i % 4;
                fs.add(pool.submit(() -> s.load(vod(), "f" + n + ".json", vodJson(n * 10), LANG)));
                fs.add(pool.submit(() -> s.preview(vod(), "", "", "", "", LANG).map(p -> new CollectPreviewService.LoadOutcome(true, "", 0))
                        .orElse(new CollectPreviewService.LoadOutcome(true, "", 0))));
            }
            for (Future<CollectPreviewService.LoadOutcome> f : fs) {
                assertThat(f.get().ok()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(cacheFiles()).hasSize(1);
        CollectPreviewService.Preview p = s.preview(vod(), "", "", "", "1000", LANG).orElseThrow();
        assertThat(p.sourceName()).matches("f[1-4]\\.json");
        assertThat(p.total()).isEqualTo(Long.parseLong(p.sourceName().substring(1, 2)) * 10);
    }

    private List<Path> cacheFiles() throws Exception {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }
}
