package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.PhpInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B19 入库规则里黄金场景（{@code ReceiveGoldenMySqlIT}，经 Receive 只能走 {@code $param = []}）覆盖不到的分支与旧 PHP 函数（27 文档第 3、8 节）：
 * 后台采集的 {@code opt}、{@code filter}/{@code filter_from}，随机项的取值范围，{@code vod_search_optimise} 含 collect 时的查重条件，
 * 登记差异 D-B19-3/4/5，以及 Receive 的参数整理。
 */
class VodIngestTest {

    private static final Map<String, String> LANG = Map.of("model/collect/add_ok", "新加入库，成功ok。",
            "model/collect/not_check_add", "数据操作没有勾选新增，跳过。", "model/collect/not_check_update", "数据操作没有勾选更新，跳过。",
            "model/collect/not_need_update", "无需更新。", "model/collect/playgroup_add_ok", "播放组(%s)，新增ok。",
            "slice", "片", "drama", "剧");

    /** 内存里的 Store：记下查重条件与写入。 */
    static final class FakeStore implements VodIngest.Store {
        Map<String, String> existing;
        final List<VodIngest.VodMatch> matches = new ArrayList<>();
        final List<Map<String, Object>> inserted = new ArrayList<>();
        final List<Map<String, Object>> updated = new ArrayList<>();
        final List<Long> appended = new ArrayList<>();

        @Override
        public Map<String, String> find(VodIngest.VodMatch m) {
            matches.add(m);
            return existing;
        }

        @Override
        public long insert(Map<String, Object> row) {
            inserted.add(row);
            return 41;
        }

        @Override
        public void update(long vodId, Map<String, Object> columns) {
            updated.add(columns);
        }

        @Override
        public List<Long> actorSearchIds(String word) {
            return word.equals("甲") ? List.of(3L, 5L) : List.of();
        }

        @Override
        public void appendSearch(long vodId, Map<String, Object> row) {
            appended.add(vodId);
        }
    }

    private static Map<Object, Object> config(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>(Map.of("status", "1", "hits_start", "0", "hits_end", "0", "updown_start", "0",
                "updown_end", "0", "score", "0", "class_filter", "1", "inrule", ",f,g", "uprule", ",a", "filter", "色戒"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static VodIngest ingest(Map<Object, Object> config, boolean search, java.util.function.IntBinaryOperator random) {
        Map<Object, Object> players = new LinkedHashMap<>(Map.of("dplayer", Map.of("show", "x"), "videojs", Map.of("show", "y")));
        Map<Object, Object> downers = new LinkedHashMap<>(Map.of("http", Map.of("show", "z")));
        return new VodIngest(new VodIngest.Settings(config, players, downers, id -> Map.of("type_pid", "1"), search,
                k -> LANG.getOrDefault(k, k), random));
    }

    private static Map<Object, Object> row(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>(Map.of("vod_name", "片", "type_id", "6", "vod_content", "正文"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void optSkipsAddOrUpdate() {
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config(), false, (a, b) -> a);
        assertThat(v.process(row(), new VodIngest.Param("2", null, null, null), store, 100).des()).isEqualTo("数据操作没有勾选新增，跳过。");
        assertThat(store.inserted).isEmpty();
        store.existing = Map.of("vod_id", "9", "vod_lock", "0", "vod_play_from", "", "vod_play_url", "");
        assertThat(v.process(row(), new VodIngest.Param(1L, null, null, null), store, 100).des()).isEqualTo("数据操作没有勾选更新，跳过。");
        assertThat(store.updated).isEmpty();
    }

    @Test
    void filterOnInsertKeepsOnlyListedPlayersAndEmptiesWhenNoneMatch() {
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config(), false, (a, b) -> a);
        Map<Object, Object> r = row("vod_play_from", "dplayer$$$videojs", "vod_play_url", "a$$$b", "vod_down_from", "http",
                "vod_down_url", "d");
        v.process(r, new VodIngest.Param(null, "1", "videojs", null), store, 100);
        assertThat(store.inserted.get(0)).containsEntry("vod_play_from", "videojs").containsEntry("vod_play_url", "b")
                .containsEntry("vod_down_from", "").containsEntry("vod_down_url", "");
        // filter_from 是子串匹配（旧版 strpos(',filter_from,', $from)）
        v.process(r, new VodIngest.Param(null, 2L, "xdplayerx", null), store, 100);
        assertThat(store.inserted.get(1)).containsEntry("vod_play_from", "dplayer");
        // D-B19-5：一组都不匹配时旧版 join(null) 在 PHP 8 报错；新版四列为空串
        v.process(r, new VodIngest.Param(null, "1", "iframe", null), store, 100);
        assertThat(store.inserted.get(2)).containsEntry("vod_play_from", "").containsEntry("vod_play_url", "");
    }

    @Test
    void filterThreeOnUpdateMergesOnlyListedGroups() {
        FakeStore store = new FakeStore();
        store.existing = new LinkedHashMap<>(Map.of("vod_id", "9", "vod_lock", "0", "vod_play_from", "dplayer", "vod_play_url", "x",
                "vod_play_server", "", "vod_play_note", ""));
        VodIngest v = ingest(config(), false, (a, b) -> a);
        Map<Object, Object> r = row("vod_play_from", "dplayer$$$videojs", "vod_play_url", "a$$$b");
        VodIngest.Outcome o = v.process(r, new VodIngest.Param(null, "3", "videojs", null), store, 100);
        assertThat(o.des()).isEqualTo("播放组(videojs)，新增ok。");
        assertThat(store.updated.get(0)).containsEntry("vod_play_from", "dplayer$$$videojs").containsEntry("vod_play_url", "x$$$b");
    }

    @Test
    void randomItemsUseLegacyRanges() {
        List<int[]> calls = new ArrayList<>();
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config("hits_start", "5", "hits_end", "50", "updown_start", "2", "updown_end", "9", "score", "1"), false,
                (a, b) -> {
                    calls.add(new int[] {a, b});
                    return b;
                });
        v.process(row(), VodIngest.Param.NONE, store, 100);
        assertThat(calls).extracting(c -> c[0] + "-" + c[1]).containsExactly("5-50", "5-50", "5-50", "5-50", "2-9", "2-9", "1-1000",
                "1-10");
        assertThat(store.inserted.get(0)).containsEntry("vod_hits", 50L).containsEntry("vod_up", 9L).containsEntry("vod_score_num", 1000L)
                .containsEntry("vod_score_all", 10000L).containsEntry("vod_score", 10.0);
    }

    @Test
    void searchOptimiseUsesActorResultIdsForBlendAndAppendsNewVideo() {
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config(), true, (a, b) -> a);
        v.process(row("vod_actor", "甲", "vod_director", "乙"), VodIngest.Param.NONE, store, 100);
        assertThat(store.matches.get(0).blend()).isTrue();
        assertThat(store.matches.get(0).blendIds()).containsExactly(3L, 5L);
        assertThat(store.appended).containsExactly(41L);
        v.process(row("vod_actor", "无", "vod_director", "乙"), VodIngest.Param.NONE, store, 100);
        assertThat(store.matches.get(1).blendIds()).containsExactly(0L);
    }

    @Test
    void tagRuleClearsTagBecauseRemoteTaggingWasRemoved() {
        FakeStore store = new FakeStore();
        ingest(config("tag", "1"), false, (a, b) -> a).process(row("vod_tag", "a,b"), VodIngest.Param.NONE, store, 100);
        assertThat(store.inserted.get(0)).containsEntry("vod_tag", "");
    }

    @Test
    void serialKeepsLargerIntegerAndTreatsNonNumericStoredValueAsNotInteger() {
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config("uprule", ",c"), false, (a, b) -> a);
        store.existing = new LinkedHashMap<>(Map.of("vod_id", "9", "vod_lock", "0", "vod_serial", "12"));
        v.process(row("vod_serial", "8"), VodIngest.Param.NONE, store, 100);
        assertThat(store.updated.get(0)).containsEntry("vod_serial", "12");
        // D-B19-4：库里是"全集"时旧版 floor() 在 PHP 8 报错；新版按 PHP 7 的结果写提交的值
        store.existing = new LinkedHashMap<>(Map.of("vod_id", "9", "vod_lock", "0", "vod_serial", "全集"));
        v.process(row("vod_serial", "8"), VodIngest.Param.NONE, store, 100);
        assertThat(store.updated.get(1)).containsEntry("vod_serial", "8");
    }

    @Test
    void arrayFieldsAreRejected() {
        FakeStore store = new FakeStore();
        VodIngest v = ingest(config(), false, (a, b) -> a);
        Map<Object, Object> r = row("vod_actor", new LinkedHashMap<>(Map.of(0L, "x")));
        assertThatThrownBy(() -> v.process(r, VodIngest.Param.NONE, store, 100)).isInstanceOf(VodIngest.Rejected.class);
        assertThatThrownBy(() -> v.process("text", VodIngest.Param.NONE, store, 100)).isInstanceOf(VodIngest.Rejected.class);
        assertThat(store.inserted).isEmpty();
    }

    // ------------------------------------------------------------------ 旧 PHP 函数

    @Test
    void legacyTextFunctions() {
        assertThat(IngestPhp.formatText("a/b，c|d、e,,f g", true)).isEqualTo("a,b,c,d,e,f g");
        assertThat(IngestPhp.formatText("a b", false)).isEqualTo("a,b");
        assertThat(IngestPhp.txtMerge("剧情,爱情", "动作片", true, "片", "剧")).isEqualTo("剧情,爱情,动作");
        assertThat(IngestPhp.txtMerge("剧情", "喜剧片", true, "片", "剧")).isEqualTo("剧情,喜剧");
        assertThat(IngestPhp.txtMerge("剧情", "动作片", false, "片", "剧")).isEqualTo("剧情,动作片");
        assertThat(IngestPhp.txtMerge("x", "", true, "片", "剧")).isEqualTo("x");
        IngestPhp.Pairs p = IngestPhp.txtExplain("第1季=第一季#无等号#a&amp;b=c&lt;d#=空");
        assertThat(p.from()).containsExactly("第1季", "a&b", "");
        assertThat(p.to()).containsExactly("第一季", "c<d", "空");
        assertThat(IngestPhp.repPseSyn(p, "某剧第1季")).isEqualTo("某剧第一季");
        assertThat(IngestPhp.inFilter(List.of("色戒", "", "0"), "色戒2")).isTrue();
        assertThat(IngestPhp.inFilter(List.of("", "0"), "0")).isFalse();
        assertThat(IngestPhp.trimChars("$$$a$b$$$", "$")).isEqualTo("a$b");
        assertThat(IngestPhp.macSubstring("一二三四五", 2, 1)).isEqualTo("二三");
        assertThat(IngestPhp.likeArr("a,b")).containsExactly("%a%", "%b%");
        assertThat(IngestPhp.looseEquals("1e1", "10")).isTrue();
        assertThat(IngestPhp.looseEquals(12L, "12集")).isFalse();
        assertThat(IngestPhp.looseEquals(null, "")).isTrue();
        assertThat(IngestPhp.looseEquals("abc", null)).isFalse();
        assertThat(IngestPhp.intval("2014年")).isEqualTo(2014);
        assertThat(IngestPhp.floatval(" 8.5分")).isEqualTo(8.5);
    }

    @Test
    void randomWordInsertionIsDeterministicByCrc32() {
        Object out = IngestPhp.repPseRnd(List.of("A", "B", "C"), "第一句。第二句", n -> 0);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update("第一句。第二句".getBytes(StandardCharsets.UTF_8));
        String w = List.of("A", "B", "C").get((int) (crc.getValue() % 3));
        // 旧版取前 j-1 个字符、再从第 j 个字符取起，第 j-1 个字符（"句"）被丢掉，照旧
        assertThat(out).isEqualTo("第一" + w + "。第二句");
        assertThat(IngestPhp.repPseRnd(List.of("A"), "", n -> 0)).isEqualTo("A");
        assertThat(IngestPhp.repPseRnd(List.of(), "x", n -> 0)).isEqualTo("x");
    }

    // ------------------------------------------------------------------ Receive 的参数

    @Test
    void receiveInputTrimsThenUrldecodesAndMarksQueryExpressions() {
        Map<String, PhpInput.Value> params = new LinkedHashMap<>();
        params.put("a", new PhpInput.Value(" x%2By ".getBytes(StandardCharsets.UTF_8), false));
        params.put("b", new PhpInput.Value("like".getBytes(StandardCharsets.UTF_8), false));
        params.put("c", new PhpInput.Value("not in".getBytes(StandardCharsets.UTF_8), false));
        Map<String, Object> in = ReceiveService.input(params);
        assertThat(in).containsEntry("a", "x+y").containsEntry("b", "like ").containsEntry("c", "not in ");
        assertThatThrownBy(() -> ReceiveService.input(Map.of("a", new PhpInput.Value(new byte[0], true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReceiveService.input(Map.of("a", new PhpInput.Value("%FF".getBytes(StandardCharsets.US_ASCII), false))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interfaceTypesMapRemoteNamesToLocalTypeIdsByName() {
        Map<Object, Object> maccms = Map.of("interface", Map.of("vodtype", "动作片=动作\r\n喜剧片=喜剧#不存在=孤儿#=空名#喜剧片=7"));
        Map<Object, Object> t = ReceiveService.interfaceTypes(maccms, "vodtype", Map.of("动作片", 6L, "喜剧片", 7L));
        assertThat(t).containsEntry("动作", 6L).containsEntry("喜剧", 7L).containsEntry("孤儿", 0L).containsEntry("空名", 0L)
                .containsEntry(7L, 7L);
    }
}
