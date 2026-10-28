package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.LegacyPinyin;
import com.maccms.backend.content.application.PhpText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.UnaryOperator;

/**
 * 文章、演员、角色、网址、评论入库的一行（旧 {@code Collect::art_data}、{@code actor_data}、{@code role_data}、{@code website_data}、
 * {@code comment_data} 的循环体，application/common/model/Collect.php:1126-1378、1453-1670、1725-1969、2044-2259、2314-2532；B19 增量 2，
 * 27 文档第 3.2 节）。只做计算：查重、关联查找、新增、更新都经 {@link Store}，由调用方在入库锁内执行（{@link CollectIngestService}）。
 *
 * <p>值的表示同 {@link ProvideValues}。旧代码在下列情况整页 500，新版把这一行拒绝为"参数错误"（{@link VodIngest.Rejected}，D-B19-3）：
 * 字段是数组（PHP 8 类型错误）；MySQL 严格模式下的数据错误（如缺正文）；演员、角色、网址新增时没有提交图片（旧版把 null 写进不允许 NULL 的
 * 图片列）、更新时没有提交图片而库里的图片是远程地址或为空（同上，D-B19-9）。
 */
public final class KindIngest {

    /** 种类（旧 {@code collect.<种类>} 配置与列名前缀）。 */
    public enum Kind {
        ART, ACTOR, ROLE, WEBSITE, COMMENT;

        /** 列名前缀与配置键（{@code art}、{@code actor} ……）。 */
        public String prefix() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 查重、关联查找与写入（由入库服务实现）。行的列值是库里读出的字符串。 */
    public interface Store {

        /** 文章：名称相等，{@code typeId} 非 null 时另按分类。 */
        Map<String, String> findArt(String name, String typeId);

        /** 演员：名称相等，另按性别、分类（非 null 时）。 */
        Map<String, String> findActor(String name, String sex, String typeId);

        /** 网址：名称相等，另按分类（非 null 时）。 */
        Map<String, String> findWebsite(String name, String typeId);

        /** 角色：角色名、演员名、关联视频编号都相等。 */
        Map<String, String> findRole(String name, String actor, String rid);

        /** 评论：关联编号相等，另按内容、昵称（非 null 时）。 */
        Map<String, String> findComment(String rid, String content, String name);

        /** 角色的关联视频编号（豆瓣编号非 null 时按豆瓣编号，否则按名称；主演、导演的 LIKE 条件非 null 时参与）；没有时 null。 */
        String findRoleVod(String doubanId, String vodName, List<String> actorLikes, List<String> directorLikes);

        /** 评论的关联数据编号（{@code mid} 为 1、2、3、8、9、11）；没有时 null。 */
        String findRelId(int mid, String doubanId, String name);

        /** 新增，返回自增主键（只写表里有的列）。 */
        long insert(Kind kind, Map<String, Object> row);

        /** 按主键修改。 */
        void update(Kind kind, long id, Map<String, Object> columns);
    }

    /**
     * 配置与环境。
     *
     * @param config  {@code maccms.php} 的 {@code collect.<种类>}
     * @param typePid 本站分类的 {@code type_pid}（旧 {@code $type_list[$type_id]['type_pid']}；没有该分类时 null）
     * @param lang    语言包
     * @param random  {@code rand(min, max)}
     */
    public record Settings(Map<Object, Object> config, Function<String, Object> typePid, UnaryOperator<String> lang,
                           IntBinaryOperator random) {
    }

    private final Kind kind;
    private final String p;
    private final Settings s;
    private final List<String> filterWords;
    private final List<String> rndWords;
    private final IngestPhp.Pairs pseSyn;

    public KindIngest(Kind kind, Settings settings) {
        this.kind = kind;
        this.p = kind.prefix();
        this.s = settings;
        this.filterWords = nonEmpty(IngestPhp.explode(",", c("filter")));
        this.rndWords = nonEmpty(IngestPhp.explode("#", c("words")));
        this.pseSyn = IngestPhp.txtExplain(cfg("thesaurus"));
    }

    /**
     * 处理一行。
     *
     * @param row 数据行（旧 {@code $v}）
     * @param now 当前时间（秒）
     * @return 结果；{@link VodIngest.Outcome#name()} 是旧版 {@code msg} 里放在说明前面的值（评论为内容，其他为名称）
     * @throws VodIngest.Rejected 旧版在这一行上会报错
     */
    public VodIngest.Outcome process(Object row, Store store, long now) {
        if (!(row instanceof Map<?, ?> in)) {
            throw new VodIngest.Rejected("数据行不是数组");
        }
        Map<String, Object> v = new LinkedHashMap<>();
        in.forEach((k, x) -> v.put(IngestPhp.str(k), x));
        return switch (kind) {
            case ART, ACTOR, WEBSITE -> typed(v, store, now);
            case ROLE -> role(v, store, now);
            case COMMENT -> comment(v, store, now);
        };
    }

    // ------------------------------------------------------------------ 文章、演员、网址

    private VodIngest.Outcome typed(Map<String, Object> v, Store store, long now) {
        String nameKey = p + "_name";
        if (ProvideValues.looseEqualsZero(v.get("type_id"))) {
            return red("model/collect/type_err", v.get(nameKey));
        }
        if (kind == Kind.ACTOR ? IngestPhp.empty(v.get(nameKey)) || IngestPhp.empty(v.get("actor_sex"))
                : IngestPhp.empty(v.get(nameKey))) {
            // 演员：旧代码的语言键少了开头的 m（'odel/collect/actor_data_require'），语言包里没有，原样输出键名
            return red(kind == Kind.ACTOR ? "odel/collect/actor_data_require" : "model/collect/name_err", v.get(nameKey));
        }
        if (IngestPhp.inFilter(filterWords, v.get(nameKey))) {
            return red("model/collect/name_in_filter_err", v.get(nameKey));
        }
        v.remove(p + "_id");
        stripTags(v);
        v.put(nameKey, trim(IngestPhp.str(v.get(nameKey))));
        v.put("type_id_1", IngestPhp.intval(ProvideValues.get(s.typePid().apply(IngestPhp.str(v.get("type_id"))), "type_pid")));
        prepare(v, now, kind == Kind.ART ? List.of("stint") : List.of());
        if (IngestPhp.empty(v.get(p + "_blurb"))) {
            Object content = stringOf(v.get(p + "_content"));
            if (kind == Kind.ART) {
                content = IngestPhp.replace((String) content, "$$$", "");
            }
            v.put(p + "_blurb", IngestPhp.macSubstring(IngestPhp.stripTags(content), 100));
        }
        String inrule = c("inrule");
        String name = IngestPhp.str(v.get(nameKey));
        Map<String, String> info;
        switch (kind) {
            case ART -> {
                String typeId = inrule.contains("b") ? IngestPhp.str(v.get("type_id")) : null;
                rebuildArtPages(v);
                info = store.findArt(name, typeId);
            }
            case ACTOR -> info = store.findActor(name, inrule.contains("b") ? IngestPhp.str(v.get("actor_sex")) : null,
                    inrule.contains("c") ? IngestPhp.str(v.get("type_id")) : null);
            default -> info = store.findWebsite(name, inrule.contains("b") ? IngestPhp.str(v.get("type_id")) : null);
        }
        if (info == null) {
            // 图片同步不实施（E02）：保持提交的值。文章转成字符串（旧 (string)$tmp['pic']），演员、网址照旧不转（没有时为 null，写库被拒绝）
            v.put(p + "_pic", kind == Kind.ART ? IngestPhp.str(v.get("art_pic")) : v.get(p + "_pic"));
            store.insert(kind, v);
            return green("model/collect/add_ok", v.get(nameKey));
        }
        if (IngestPhp.empty(cfg("uprule"))) {
            return red("model/collect/uprule_empty", v.get(nameKey));
        }
        if (IngestPhp.looseEquals(1L, info.get(p + "_lock"))) {
            return red("model/collect/data_lock", v.get(nameKey));
        }
        v.remove(p + "_time_add");
        String uprule = "," + c("uprule");
        Map<String, Object> update = new LinkedHashMap<>();
        switch (kind) {
            case ART -> {
                changed(v, info, update, uprule, 'a', "art_content");
                changed(v, info, update, uprule, 'b', "art_author");
                changed(v, info, update, uprule, 'c', "art_from");
                pic(v, info, update, uprule, 'd', true);
                changed(v, info, update, uprule, 'e', "art_tag");
                changed(v, info, update, uprule, 'f', "art_blurb");
            }
            case ACTOR -> {
                changed(v, info, update, uprule, 'a', "actor_content");
                changed(v, info, update, uprule, 'b', "actor_blurb");
                changed(v, info, update, uprule, 'c', "actor_remarks");
                changed(v, info, update, uprule, 'd', "actor_works");
                pic(v, info, update, uprule, 'e', false);
            }
            default -> {
                changed(v, info, update, uprule, 'a', "website_content");
                changed(v, info, update, uprule, 'b', "website_blurb");
                changed(v, info, update, uprule, 'c', "website_remarks");
                changed(v, info, update, uprule, 'd', "website_jumpurl");
                pic(v, info, update, uprule, 'e', false);
            }
        }
        return saveUpdate(update, info, store, now, v.get(nameKey));
    }

    /**
     * 文章的分页（Collect.php:1218-1235）：标题、备注、正文各按 {@code $$$} 拆开，以正文的页数为准重新拼接（标题、备注多出的页丢弃，少的
     * 页为空串）。
     */
    private static void rebuildArtPages(Map<String, Object> v) {
        List<String> title = IngestPhp.explode("$$$", stringOf(v.get("art_title")));
        List<String> note = IngestPhp.explode("$$$", stringOf(v.get("art_note")));
        List<String> content = IngestPhp.explode("$$$", stringOf(v.get("art_content")));
        List<String> t = new ArrayList<>();
        List<String> n = new ArrayList<>();
        for (int k = 0; k < content.size(); k++) {
            t.add(k < title.size() ? title.get(k) : "");
            n.add(k < note.size() ? note.get(k) : "");
        }
        v.put("art_title", String.join("$$$", t));
        v.put("art_note", String.join("$$$", n));
        v.put("art_content", String.join("$$$", content));
    }

    // ------------------------------------------------------------------ 角色

    private VodIngest.Outcome role(Map<String, Object> v, Store store, long now) {
        if (IngestPhp.empty(v.get("role_name")) || IngestPhp.empty(v.get("role_actor")) || IngestPhp.empty(v.get("vod_name"))) {
            return red("model/collect/role_data_require", v.get("role_name"));
        }
        if (IngestPhp.inFilter(filterWords, v.get("role_name"))) {
            return red("model/collect/name_in_filter_err", v.get("role_name"));
        }
        v.remove("role_id");
        stripTags(v);
        prepare(v, now, List.of());
        String inrule = c("inrule");
        String doubanId = null;
        if (!IngestPhp.empty(v.get("douban_id"))) {
            doubanId = IngestPhp.str(v.remove("douban_id"));
        }
        String actor = IngestPhp.str(v.get("role_actor"));
        String rid = store.findRoleVod(doubanId, doubanId == null ? IngestPhp.str(v.get("vod_name")) : null,
                inrule.contains("c") ? IngestPhp.likeArr(actor) : null, inrule.contains("d") ? IngestPhp.likeArr(actor) : null);
        if (rid == null) {
            return red("model/collect/not_found_rel_vod", v.get("role_name"));
        }
        v.put("role_rid", rid);
        Map<String, String> info = store.findRole(IngestPhp.str(v.get("role_name")), actor, rid);
        if (info == null) {
            v.put("role_pic", v.get("role_pic"));
            store.insert(kind, v);
            return green("model/collect/add_ok", v.get("role_name"));
        }
        if (IngestPhp.empty(cfg("uprule"))) {
            return red("model/collect/uprule_empty", v.get("role_name"));
        }
        if (IngestPhp.looseEquals(1L, info.get("role_lock"))) {
            return red("model/collect/data_lock", v.get("role_name"));
        }
        v.remove("role_time_add");
        String uprule = "," + c("uprule");
        Map<String, Object> update = new LinkedHashMap<>();
        changed(v, info, update, uprule, 'a', "role_content");
        changed(v, info, update, uprule, 'b', "role_remarks");
        pic(v, info, update, uprule, 'c', false);
        return saveUpdate(update, info, store, now, v.get("role_name"));
    }

    // ------------------------------------------------------------------ 评论

    private VodIngest.Outcome comment(Map<String, Object> v, Store store, long now) {
        if (IngestPhp.empty(v.get("comment_name")) || IngestPhp.empty(v.get("comment_content")) || IngestPhp.empty(v.get("rel_name"))) {
            return red("model/collect/comment_data_require", v.get("comment_content"));
        }
        if (IngestPhp.inFilter(filterWords, v.get("comment_content"))) {
            return red("model/collect/name_in_filter_err", v.get("comment_content"));
        }
        v.remove("comment_id");
        stripTags(v);
        v.put("comment_time", now);
        v.put("comment_status", IngestPhp.intval(cfg("status")));
        for (String f : List.of("comment_up", "comment_down", "comment_mid")) {
            v.put(f, IngestPhp.intval(v.get(f)));
        }
        Object ip = v.get("comment_ip");
        if (!IngestPhp.empty(ip) && !IngestPhp.isNumeric(IngestPhp.str(ip))) {
            v.put("comment_ip", ipLong(IngestPhp.str(ip)));
        }
        updown(v);
        pse(v);
        String inrule = c("inrule");
        String content = inrule.contains("b") ? IngestPhp.str(v.get("comment_content")) : null;
        String name = inrule.contains("c") ? IngestPhp.str(v.get("comment_name")) : null;
        String rid;
        if (IngestPhp.empty(v.get("rel_id"))) {
            long mid = (Long) v.get("comment_mid");
            String relName = IngestPhp.str(v.get("rel_name"));
            if (mid == 1) {
                rid = IngestPhp.empty(v.get("douban_id")) ? store.findRelId(1, null, relName)
                        : store.findRelId(1, IngestPhp.str(v.remove("douban_id")), null);
            } else if (mid == 2 || mid == 3 || mid == 8 || mid == 9 || mid == 11) {
                rid = store.findRelId((int) mid, null, relName);
            } else {
                rid = null;
            }
        } else {
            rid = IngestPhp.str(v.get("rel_id"));
        }
        if (IngestPhp.empty(rid)) {
            return red("model/collect/not_found_rel_data", v.get("comment_content"));
        }
        v.put("comment_rid", rid);
        Map<String, String> info = content == null && name == null ? null : store.findComment(rid, content, name);
        if (info == null) {
            store.insert(kind, v);
            return green("model/collect/add_ok", v.get("comment_content"));
        }
        if (IngestPhp.empty(cfg("uprule"))) {
            return red("model/collect/uprule_empty", v.get("comment_content"));
        }
        Map<String, Object> update = new LinkedHashMap<>();
        // 旧版的更新项 a 比较的是刚设成当前时间的 comment_time，所以库里的时间不是这一秒时总会更新，写入的也是当前时间
        if (("," + c("uprule")).contains("a") && !IngestPhp.looseEquals(v.get("comment_time"), info.get("comment_time"))) {
            update.put("comment_time", now);
        }
        return saveUpdate(update, info, store, now, v.get("comment_content"));
    }

    /**
     * {@code mac_get_ip_long($ip)}（application/common.php:990-999）：{@code sprintf('%u', ip2long($ip))}，不是点分十进制的 IPv4 时为 0；
     * {@code 255.255.255.255} 也是 0。
     */
    static long ipLong(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return 0;
        }
        long out = 0;
        for (String x : parts) {
            if (!x.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(x) > 255) {
                return 0;
            }
            out = out * 256 + Integer.parseInt(x);
        }
        return out >= 0xFFFFFFFFL ? 0 : out;
    }

    // ------------------------------------------------------------------ 共同的整理

    /** 除键名含 {@code _content} 外的字段 {@code strip_tags}（数组字段旧版报错，拒绝）。 */
    private static void stripTags(Map<String, Object> v) {
        for (Map.Entry<String, Object> e : v.entrySet()) {
            if (!e.getKey().contains("_content")) {
                if (e.getValue() instanceof Map<?, ?>) {
                    throw new VodIngest.Rejected("字段是数组");
                }
                e.setValue(IngestPhp.stripTags(e.getValue()));
            }
        }
    }

    /**
     * 文章、演员、角色、网址共同的整理：拼音与首字母（提交的值不起作用）、新增与修改时间、状态取配置（提交的不起作用）、整数与评分字段、
     * 随机点击顶踩评分、伪原创。
     */
    private void prepare(Map<String, Object> v, long now, List<String> extraInts) {
        String en = LegacyPinyin.get(IngestPhp.str(v.get(p + "_name")));
        v.put(p + "_en", en);
        v.put(p + "_letter", IngestPhp.letter(en));
        v.put(p + "_time_add", now);
        v.put(p + "_time", now);
        // 旧版先用配置覆盖状态，再"非空时 intval"，所以提交的状态不起作用
        v.put(p + "_status", IngestPhp.intval(cfg("status")));
        List<String> ints = new ArrayList<>(List.of("lock", "level", "hits", "hits_day", "hits_week", "hits_month"));
        ints.addAll(extraInts);
        ints.addAll(List.of("up", "down"));
        for (String f : ints) {
            v.put(p + "_" + f, IngestPhp.intval(v.get(p + "_" + f)));
        }
        v.put(p + "_score", IngestPhp.floatval(v.get(p + "_score")));
        v.put(p + "_score_all", IngestPhp.intval(v.get(p + "_score_all")));
        v.put(p + "_score_num", IngestPhp.intval(v.get(p + "_score_num")));
        if (ProvideValues.greaterThanZero(cfg("hits_start")) && ProvideValues.greaterThanZero(cfg("hits_end"))) {
            long a = IngestPhp.intval(cfg("hits_start"));
            long b = IngestPhp.intval(cfg("hits_end"));
            for (String f : List.of("hits", "hits_day", "hits_week", "hits_month")) {
                v.put(p + "_" + f, (long) rand(a, b));
            }
        }
        updown(v);
        if (VodIngest.equalsOne(cfg("score"))) {
            long num = s.random().applyAsInt(1, 1000);
            long all = num * s.random().applyAsInt(1, 10);
            v.put(p + "_score_num", num);
            v.put(p + "_score_all", all);
            v.put(p + "_score", Math.round((double) all / num * 10) / 10.0);
        }
        pse(v);
    }

    /** {@code if ($config['updown_start'] > 0 && $config['updown_end'])}：顶踩取随机数。 */
    private void updown(Map<String, Object> v) {
        if (ProvideValues.greaterThanZero(cfg("updown_start")) && !IngestPhp.empty(cfg("updown_end"))) {
            long a = IngestPhp.intval(cfg("updown_start"));
            long b = IngestPhp.intval(cfg("updown_end"));
            v.put(p + "_up", (long) rand(a, b));
            v.put(p + "_down", (long) rand(a, b));
        }
    }

    /** 正文的随机词与同义词替换（{@code psernd}、{@code psesyn}）。 */
    private void pse(Map<String, Object> v) {
        String f = p + "_content";
        if (VodIngest.equalsOne(cfg("psernd"))) {
            v.put(f, IngestPhp.repPseRnd(rndWords, v.get(f), n -> s.random().applyAsInt(0, n - 1)));
        }
        if (VodIngest.equalsOne(cfg("psesyn"))) {
            v.put(f, IngestPhp.repPseSyn(pseSyn, v.get(f)));
        }
    }

    /**
     * 图片的更新项：库里的图片是 http 开头或为空，且提交的值与库里不同时更新。文章写 {@code (string)} 后的值；演员、角色、网址照旧写原值
     * （没有提交时为 null，写库被拒绝，D-B19-9）。
     */
    private void pic(Map<String, Object> v, Map<String, String> info, Map<String, Object> update, String uprule, char rule,
                     boolean asString) {
        if (uprule.indexOf(rule) < 0) {
            return;
        }
        String old = info.get(p + "_pic");
        boolean remote = old == null || old.startsWith("http") || IngestPhp.empty(old);
        Object x = v.get(p + "_pic");
        if (remote && !IngestPhp.looseEquals(x, old)) {
            update.put(p + "_pic", asString ? IngestPhp.str(x) : x);
        }
    }

    /** {@code if (strpos(',' . uprule, x) !== false && !empty($v[f]) && $v[f] != $info[f]) $update[f] = $v[f]}。 */
    private static void changed(Map<String, Object> v, Map<String, String> info, Map<String, Object> update, String uprule, char rule,
                                String f) {
        Object x = v.get(f);
        if (uprule.indexOf(rule) >= 0 && !IngestPhp.empty(x) && !IngestPhp.looseEquals(x, info.get(f))) {
            update.put(f, x);
        }
    }

    /** 有更新项时写修改时间并按主键修改（绿色，说明为空）；没有时"无需更新"。 */
    private VodIngest.Outcome saveUpdate(Map<String, Object> update, Map<String, String> info, Store store, long now, Object name) {
        if (update.isEmpty()) {
            return red("model/collect/not_need_update", name);
        }
        update.put(p + "_time", now);
        store.update(kind, Long.parseLong(info.get(p + "_id")), update);
        return new VodIngest.Outcome(true, "", nameOf(name));
    }

    // ------------------------------------------------------------------ 工具

    /** {@code (string)$x}；数组旧版报错——拒绝。 */
    private static String stringOf(Object x) {
        if (x instanceof Map<?, ?>) {
            throw new VodIngest.Rejected("字段是数组");
        }
        return IngestPhp.str(x);
    }

    private static String trim(String x) {
        return IngestPhp.utf8(PhpText.trim(IngestPhp.bytes(x)));
    }

    /** 结果里的名称（旧 {@code $v['xx_name'] . ' ' . $des}；数组在 PHP 里是 "Array"）。 */
    private static String nameOf(Object name) {
        return name instanceof Map<?, ?> ? "Array" : IngestPhp.str(name);
    }

    private VodIngest.Outcome red(String key, Object name) {
        return new VodIngest.Outcome(false, s.lang().apply(key), nameOf(name));
    }

    private VodIngest.Outcome green(String key, Object name) {
        return new VodIngest.Outcome(true, s.lang().apply(key), nameOf(name));
    }

    private int rand(long a, long b) {
        return s.random().applyAsInt((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, a)),
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, b)));
    }

    private static List<String> nonEmpty(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String x : l) {
            if (!x.isEmpty() && !x.equals("0")) {
                out.add(x);
            }
        }
        return out;
    }

    private Object cfg(String k) {
        return ProvideValues.get(s.config(), k);
    }

    private String c(String k) {
        return IngestPhp.str(cfg(k));
    }
}
