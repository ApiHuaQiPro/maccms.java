package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B19 增量 2 入库规则里黄金场景（{@code ReceiveGoldenMySqlIT} 的 kinds 部分）覆盖不到或不便覆盖的分支（27 文档第 3.2、8 节）：
 * 数组字段的拒绝（D-B19-3）、演员必填的语言键、文章分页的对齐、评论不查重时直接新增、角色的豆瓣编号与 LIKE 条件、
 * {@code mac_get_ip_long}、随机项的取值范围、伪原创只改正文。
 */
class KindIngestTest {

    private static final Map<String, String> LANG = Map.of("model/collect/add_ok", "新加入库，成功ok。",
            "model/collect/not_need_update", "无需更新。", "model/collect/type_err", "分类未绑定，跳过err",
            "model/collect/not_found_rel_data", "未找到相关数据无法关联，跳过。");

    /** 内存里的 Store：记下查重条件与写入。 */
    static final class FakeStore implements KindIngest.Store {
        Map<String, String> existing;
        String relId = "7";
        final List<List<Object>> finds = new ArrayList<>();
        final List<Map<String, Object>> inserted = new ArrayList<>();
        final List<Map<String, Object>> updated = new ArrayList<>();

        private Map<String, String> find(Object... args) {
            finds.add(java.util.Arrays.asList(args));
            return existing;
        }

        @Override
        public Map<String, String> findArt(String name, String typeId) {
            return find("art", name, typeId);
        }

        @Override
        public Map<String, String> findActor(String name, String sex, String typeId) {
            return find("actor", name, sex, typeId);
        }

        @Override
        public Map<String, String> findWebsite(String name, String typeId) {
            return find("website", name, typeId);
        }

        @Override
        public Map<String, String> findRole(String name, String actor, String rid) {
            return find("role", name, actor, rid);
        }

        @Override
        public Map<String, String> findComment(String rid, String content, String name) {
            return find("comment", rid, content, name);
        }

        @Override
        public String findRoleVod(String doubanId, String vodName, List<String> actorLikes, List<String> directorLikes) {
            finds.add(java.util.Arrays.asList("roleVod", doubanId, vodName, actorLikes, directorLikes));
            return relId;
        }

        @Override
        public String findRelId(int mid, String doubanId, String name) {
            finds.add(java.util.Arrays.asList("rel", mid, doubanId, name));
            return relId;
        }

        @Override
        public long insert(KindIngest.Kind kind, Map<String, Object> row) {
            inserted.add(row);
            return 41;
        }

        @Override
        public void update(KindIngest.Kind kind, long id, Map<String, Object> columns) {
            updated.add(columns);
        }
    }

    private static Map<Object, Object> config(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>(Map.of("status", "1", "hits_start", "0", "hits_end", "0", "updown_start", "0",
                "updown_end", "0", "score", "0", "inrule", ",b", "uprule", ",a", "filter", "禁词"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static KindIngest ingest(KindIngest.Kind kind, Map<Object, Object> config, java.util.function.IntBinaryOperator random) {
        return new KindIngest(kind, new KindIngest.Settings(config, id -> Map.of("type_pid", "2"), k -> LANG.getOrDefault(k, k),
                random));
    }

    private static Map<Object, Object> row(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void arrayFieldsAreRejected() {
        FakeStore store = new FakeStore();
        KindIngest art = ingest(KindIngest.Kind.ART, config(), (a, b) -> a);
        assertThatThrownBy(() -> art.process(row("art_name", "文", "type_id", "8", "art_tag", Map.of("0", "x")), store, 100))
                .isInstanceOf(VodIngest.Rejected.class);
        // 键名含 _content 的字段不 strip_tags，但文章分页要把它当字符串拆开
        assertThatThrownBy(() -> art.process(row("art_name", "文", "type_id", "8", "art_content", Map.of("0", "x")), store, 100))
                .isInstanceOf(VodIngest.Rejected.class);
        assertThat(store.inserted).isEmpty();
    }

    @Test
    void actorRequiresSexWithTheLegacyMisspelledLangKey() {
        VodIngest.Outcome o = ingest(KindIngest.Kind.ACTOR, config(), (a, b) -> a).process(row("actor_name", "甲", "type_id", "9"),
                new FakeStore(), 100);
        assertThat(o.green()).isFalse();
        assertThat(o.des()).isEqualTo("odel/collect/actor_data_require");
        assertThat(o.name()).isEqualTo("甲");
    }

    @Test
    void artPagesFollowTheContentPageCount() {
        FakeStore store = new FakeStore();
        ingest(KindIngest.Kind.ART, config(), (a, b) -> a).process(row("art_name", " 文 ", "type_id", "8", "art_title", "t1",
                "art_note", "n1$$$n2$$$n3", "art_content", "c1$$$c2"), store, 100);
        Map<String, Object> r = store.inserted.get(0);
        assertThat(r.get("art_title")).isEqualTo("t1$$$");
        assertThat(r.get("art_note")).isEqualTo("n1$$$n2");
        assertThat(r.get("art_name")).isEqualTo("文");
        assertThat(r.get("art_blurb")).isEqualTo("c1c2");
        assertThat(r.get("art_pic")).isEqualTo("");
        assertThat(r.get("type_id_1")).isEqualTo(2L);
        assertThat(store.finds.get(0)).containsExactly("art", "文", "8");
    }

    @Test
    void actorAndWebsiteKeepANullPictureForTheWrite() {
        FakeStore store = new FakeStore();
        ingest(KindIngest.Kind.WEBSITE, config(), (a, b) -> a).process(row("website_name", "站", "type_id", "10",
                "website_content", "c"), store, 100);
        Map<String, Object> r = store.inserted.get(0);
        assertThat(r).containsKey("website_pic");
        assertThat(r.get("website_pic")).isNull();
        assertThatThrownBy(() -> CollectIngestService.columns(com.maccms.backend.content.application.AdminTable.WEBSITE, r))
                .isInstanceOf(VodIngest.Rejected.class);
    }

    @Test
    void updateRulesCompareLooselyAndSkipLockedRows() {
        FakeStore store = new FakeStore();
        store.existing = Map.of("actor_id", "3", "actor_lock", "0", "actor_content", "1.0", "actor_blurb", "b",
                "actor_pic", "upload/x.jpg");
        KindIngest actor = ingest(KindIngest.Kind.ACTOR, config("uprule", ",a,e"), (a, b) -> a);
        // "1" == "1.0"（数字串按数值）；库里是本地图片时不改图片
        VodIngest.Outcome o = actor.process(row("actor_name", "甲", "actor_sex", "男", "type_id", "9", "actor_content", "1",
                "actor_pic", "http://p"), store, 100);
        assertThat(o.des()).isEqualTo("无需更新。");
        assertThat(store.updated).isEmpty();
        store.existing = Map.of("actor_id", "3", "actor_lock", "1");
        assertThat(actor.process(row("actor_name", "甲", "actor_sex", "男", "type_id", "9", "actor_content", "新"), store, 100).des())
                .isEqualTo("model/collect/data_lock");
    }

    @Test
    void commentWithoutDedupeRulesAlwaysInserts() {
        FakeStore store = new FakeStore();
        store.existing = Map.of("comment_id", "1", "comment_time", "1");
        VodIngest.Outcome o = ingest(KindIngest.Kind.COMMENT, config("inrule", ","), (a, b) -> a).process(row("comment_name", "u",
                "comment_content", "<b>好</b>", "comment_mid", "2", "rel_name", "文<i>章</i>", "comment_ip", "10.0.0.1"), store, 100);
        assertThat(o.green()).isTrue();
        assertThat(o.name()).isEqualTo("<b>好</b>");
        assertThat(store.finds).containsExactly(java.util.Arrays.asList("rel", 2, null, "文章"));
        Map<String, Object> r = store.inserted.get(0);
        assertThat(r.get("comment_rid")).isEqualTo("7");
        assertThat(r.get("comment_ip")).isEqualTo(167772161L);
        assertThat(r.get("comment_status")).isEqualTo(1L);
    }

    @Test
    void commentRelationNeedsAKnownModule() {
        FakeStore store = new FakeStore();
        KindIngest c = ingest(KindIngest.Kind.COMMENT, config(), (a, b) -> a);
        assertThat(c.process(row("comment_name", "u", "comment_content", "x", "comment_mid", "4", "rel_name", "r"), store, 100).des())
                .isEqualTo("未找到相关数据无法关联，跳过。");
        // 按整个整数比较（不截成 32 位：4294967297 不是 1）
        assertThat(c.process(row("comment_name", "u", "comment_content", "x", "comment_mid", "4294967297", "rel_name", "r"), store,
                100).des()).isEqualTo("未找到相关数据无法关联，跳过。");
        assertThat(store.finds).isEmpty();
        // 豆瓣编号只对视频（mid 1）生效
        c.process(row("comment_name", "u", "comment_content", "x", "comment_mid", "1", "rel_name", "r", "douban_id", "9"), store, 100);
        assertThat(store.finds.get(0)).isEqualTo(java.util.Arrays.asList("rel", 1, "9", null));
        assertThat(store.inserted.get(0)).doesNotContainKey("douban_id");
    }

    @Test
    void roleUsesDoubanIdOrNameAndLikePatterns() {
        FakeStore store = new FakeStore();
        KindIngest r = ingest(KindIngest.Kind.ROLE, config("inrule", ",c,d"), (a, b) -> a);
        r.process(row("role_name", "角", "role_actor", "甲,乙", "vod_name", "剧", "douban_id", "12", "role_content", "c",
                "role_pic", "p"), store, 100);
        assertThat(store.finds.get(0)).isEqualTo(java.util.Arrays.asList("roleVod", "12", null, List.of("%甲%", "%乙%"),
                List.of("%甲%", "%乙%")));
        assertThat(store.finds.get(1)).containsExactly("role", "角", "甲,乙", "7");
        assertThat(store.inserted.get(0)).containsEntry("role_rid", "7").doesNotContainKey("douban_id");
        store.relId = null;
        assertThat(r.process(row("role_name", "角", "role_actor", "甲", "vod_name", "剧"), store, 100).des())
                .isEqualTo("model/collect/not_found_rel_vod");
    }

    @Test
    void ipLongMatchesIp2long() {
        assertThat(KindIngest.ipLong("1.2.3.4")).isEqualTo(16909060L);
        assertThat(KindIngest.ipLong("255.255.255.254")).isEqualTo(4294967294L);
        assertThat(KindIngest.ipLong("255.255.255.255")).isZero();
        assertThat(KindIngest.ipLong("1.2.3")).isZero();
        assertThat(KindIngest.ipLong("1.2.3.256")).isZero();
        assertThat(KindIngest.ipLong("abc")).isZero();
        assertThat(KindIngest.ipLong("::1")).isZero();
    }

    @Test
    void randomItemsStayInTheConfiguredRange() {
        FakeStore store = new FakeStore();
        List<int[]> calls = new ArrayList<>();
        ingest(KindIngest.Kind.ART, config("hits_start", "5", "hits_end", "9", "updown_start", "2", "updown_end", "3", "score", "1"),
                (a, b) -> {
                    calls.add(new int[]{a, b});
                    return b;
                }).process(row("art_name", "文", "type_id", "8"), store, 100);
        Map<String, Object> r = store.inserted.get(0);
        assertThat(r.get("art_hits")).isEqualTo(9L);
        assertThat(r.get("art_down")).isEqualTo(3L);
        assertThat(r.get("art_score_num")).isEqualTo(1000L);
        assertThat(r.get("art_score_all")).isEqualTo(10000L);
        assertThat(r.get("art_score")).isEqualTo(10.0);
        assertThat(calls).extracting(x -> x[0] + "-" + x[1]).containsExactly("5-9", "5-9", "5-9", "5-9", "2-3", "2-3", "1-1000",
                "1-10");
        // 评论只有顶踩随机
        FakeStore cs = new FakeStore();
        ingest(KindIngest.Kind.COMMENT, config("hits_start", "5", "hits_end", "9", "updown_start", "2", "updown_end", "3", "inrule",
                ","), (a, b) -> a).process(row("comment_name", "u", "comment_content", "x", "comment_mid", "1", "rel_name", "r"), cs, 100);
        assertThat(cs.inserted.get(0)).containsEntry("comment_up", 2L).doesNotContainKey("comment_hits");
    }

    @Test
    void pseudoOriginalOnlyTouchesTheContent() {
        FakeStore store = new FakeStore();
        ingest(KindIngest.Kind.WEBSITE, config("psesyn", "1", "thesaurus", "好=佳"), (a, b) -> a).process(row("website_name", "好站",
                "type_id", "10", "website_content", "好网站", "website_pic", ""), store, 100);
        Map<String, Object> r = store.inserted.get(0);
        assertThat(r.get("website_name")).isEqualTo("好站");
        assertThat(r.get("website_content")).isEqualTo("佳网站");
        assertThat(r.get("website_blurb")).isEqualTo("佳网站");
    }

    @Test
    void typeZeroAndFilterWordsAreSkippedBeforeAnyLookup() {
        FakeStore store = new FakeStore();
        KindIngest w = ingest(KindIngest.Kind.WEBSITE, config(), (a, b) -> a);
        assertThat(w.process(row("website_name", "站"), store, 100).des()).isEqualTo("分类未绑定，跳过err");
        assertThat(w.process(row("website_name", "禁词站", "type_id", "10"), store, 100).des())
                .isEqualTo("model/collect/name_in_filter_err");
        assertThat(store.finds).isEmpty();
    }
}
