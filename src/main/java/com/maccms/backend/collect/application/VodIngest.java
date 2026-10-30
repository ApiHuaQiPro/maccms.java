package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.LegacyPinyin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.UnaryOperator;

/**
 * 视频入库的一行（旧 {@code Collect::vod_data} 的循环体，application/common/model/Collect.php:446-994；27 文档第 3 节）。只做计算：查重、
 * 新增、更新都经 {@link Store}，由调用方在入库锁内执行（{@link CollectIngestService}）。
 *
 * <p>值的表示同 {@link ProvideValues}。旧代码对数组字段调用 {@code strip_tags} 在 PHP 8 上抛类型错误（整页 500），新版把这样的行拒绝为
 * "参数错误"（{@link Rejected}，D-B19-3）。
 */
public final class VodIngest {

    /** 旧 {@code VodValidate::formatDataBeforeDb} 的字段与 {@code mb_substr} 长度（common/validate/Vod.php:26-74，同 B13）。 */
    static final Map<String, Integer> FORMAT_FIELDS = formatFields();

    /** 查重、写入与搜索缓存（由入库服务实现）。 */
    public interface Store {

        /** 按条件找一行（旧 {@code model('Vod')->where(...)->find()}，不排序取第一行）；没有时 null。列值是库里读出的字符串。 */
        Map<String, String> find(VodMatch match);

        /** 新增，返回自增主键（旧 {@code insert($v, false, true)}，只写表里有的列）。 */
        long insert(Map<String, Object> row);

        /** 按主键修改。 */
        void update(long vodId, Map<String, Object> columns);

        /** 旧 {@code VodSearch::getResultIdList($word, 'vod_actor', true)}（只在 {@code vod_search_optimise} 含 collect 时调用）。 */
        List<Long> actorSearchIds(String word);

        /** 旧 {@code VodSearch::checkAndUpdateTopResults(['vod_id' => $id] + $v, true)}（同上）。 */
        void appendSearch(long vodId, Map<String, Object> row);
    }

    /**
     * 查重条件（旧 {@code $where} 与 {@code $GLOBALS['blend']}）。值为 null 的项不参与。
     *
     * @param actorLikes {@code vod_actor LIKE} 的模式（任一匹配；旧 {@code ['like', mac_like_arr(...), 'OR']}，不转义通配符）
     * @param blend      主演与导演同时参与时为真：{@code (vod_director = ? OR <主演条件>)}，主演条件是 {@code blendIds} 非空时的
     *                   {@code vod_id IN (...)}，否则 {@code actorLikes}
     */
    public record VodMatch(String name, String typeId, String year, String area, String lang, List<String> actorLikes,
                           String director, boolean blend, List<Long> blendIds) {
    }

    /**
     * 一次入库的参数（旧 {@code $param}：后台采集的 {@code opt}、{@code filter}、{@code filter_from}、{@code sync_pic_opt}；Receive 为空数组）。
     */
    public record Param(Object opt, Object filter, Object filterFrom, Object syncPicOpt) {
        public static final Param NONE = new Param(null, null, null, null);
    }

    /** 一行的结果：{@code green} 对应旧版的绿色（Receive 的 {@code code} 为 1，否则 1001）；{@code des} 是旧版的说明文字。 */
    public record Outcome(boolean green, String des, String name) {
    }

    /** 旧版会报错（PHP 8 类型错误或 MySQL 严格模式错误）的行。 */
    public static final class Rejected extends RuntimeException {
        public Rejected(String why) {
            super(why, null, false, false);
        }
    }

    /**
     * 配置与环境。
     *
     * @param config   {@code maccms.php} 的 {@code collect.vod}
     * @param players  {@code vodplayer.php}（键 → 非空数组）
     * @param downers  {@code voddowner.php}
     * @param typePid  本站分类的 {@code type_pid}（旧 {@code $type_list[$type_id]['type_pid']}；没有该分类时 null）
     * @param searchEnabled {@code app.vod_search_optimise} 是否含 {@code collect}
     * @param lang     语言包
     * @param random   {@code rand(min, max)}
     */
    public record Settings(Map<Object, Object> config, Map<Object, Object> players, Map<Object, Object> downers,
                           java.util.function.Function<String, Object> typePid, boolean searchEnabled, UnaryOperator<String> lang,
                           IntBinaryOperator random) {
    }

    private final Settings s;
    private final List<String> filterWords;
    private final List<String> rndWords;
    private final IngestPhp.Pairs pseName;
    private final IngestPhp.Pairs pseSyn;
    private final IngestPhp.Pairs psePlayer;
    private final IngestPhp.Pairs pseArea;
    private final IngestPhp.Pairs pseLang;

    public VodIngest(Settings settings) {
        this.s = settings;
        this.filterWords = nonEmpty(IngestPhp.explode(",", c("filter")));
        this.rndWords = nonEmpty(IngestPhp.explode("#", c("words")));
        this.pseName = IngestPhp.txtExplain(cfg("namewords"));
        this.pseSyn = IngestPhp.txtExplain(cfg("thesaurus"));
        this.psePlayer = IngestPhp.txtExplain(cfg("playerwords"));
        this.pseArea = IngestPhp.txtExplain(cfg("areawords"));
        this.pseLang = IngestPhp.txtExplain(cfg("langwords"));
    }

    /** {@code array_filter(explode(...))}：去掉空串与 {@code "0"}，保持顺序（旧代码之后按值使用，不依赖键）。 */
    private static List<String> nonEmpty(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String x : l) {
            if (!x.isEmpty() && !x.equals("0")) {
                out.add(x);
            }
        }
        return out;
    }

    /**
     * 处理一行。
     *
     * @param row 数据行（旧 {@code $v}）
     * @param now 当前时间（秒）
     * @throws Rejected 旧版在这一行上会报错
     */
    public Outcome process(Object row, Param param, Store store, long now) {
        if (!(row instanceof Map<?, ?> in)) {
            throw new Rejected("数据行不是数组");
        }
        Map<String, Object> v = new LinkedHashMap<>();
        in.forEach((k, x) -> v.put(IngestPhp.str(k), x));
        String name = IngestPhp.str(v.get("vod_name"));
        if (ProvideValues.looseEqualsZero(v.get("type_id"))) {
            return red(lang("model/collect/type_err"), name);
        }
        if (IngestPhp.empty(v.get("vod_name"))) {
            return red(lang("model/collect/name_err"), name);
        }
        if (IngestPhp.inFilter(filterWords, v.get("vod_name"))) {
            return red(lang("model/collect/name_in_filter_err"), name);
        }
        v.remove("vod_id");
        for (Map.Entry<String, Object> e : v.entrySet()) {
            if (!e.getKey().contains("_content") && !e.getKey().equals("vod_plot_detail")) {
                e.setValue(stripTags(e.getValue()));
            }
        }
        prepare(v, now);
        return save(v, param, store, now);
    }

    private static String stripTags(Object x) {
        if (x instanceof Map<?, ?>) {
            throw new Rejected("字段是数组");
        }
        return IngestPhp.stripTags(x);
    }

    /** 新增与更新共同的整理（Collect.php:465-614）。 */
    private void prepare(Map<String, Object> v, long now) {
        v.put("type_id_1", IngestPhp.intval(ProvideValues.get(s.typePid().apply(IngestPhp.str(v.get("type_id"))), "type_pid")));
        String en = LegacyPinyin.get(IngestPhp.str(v.get("vod_name")));
        v.put("vod_en", en);
        v.put("vod_letter", IngestPhp.letter(en));
        if (IngestPhp.empty(v.get("vod_time_add")) || IngestPhp.str(v.get("vod_time_add")).length() != 10) {
            v.put("vod_time_add", now);
        }
        v.put("vod_time", now);
        if (!IngestPhp.empty(v.get("vod_time_update")) && IngestPhp.str(v.get("vod_time_update")).length() == 10) {
            v.put("vod_time", IngestPhp.intval(v.get("vod_time_update")));
        }
        // 旧版先用配置覆盖 vod_status，再"非空时 intval"，所以提交的 vod_status 不起作用
        v.put("vod_status", IngestPhp.intval(cfg("status")));
        for (String f : List.of("vod_lock", "vod_year", "vod_level", "vod_hits", "vod_hits_day", "vod_hits_week", "vod_hits_month",
                "vod_stint_play", "vod_stint_down", "vod_total", "vod_serial", "vod_isend", "vod_up", "vod_down")) {
            v.put(f, IngestPhp.intval(v.get(f)));
        }
        v.put("vod_score", IngestPhp.floatval(v.get("vod_score")));
        v.put("vod_score_all", IngestPhp.intval(v.get("vod_score_all")));
        v.put("vod_score_num", IngestPhp.intval(v.get("vod_score_num")));

        v.put("vod_class", txtMerge(v.get("vod_class"), v.get("type_name")));
        for (String f : List.of("vod_actor", "vod_director", "vod_class", "vod_tag")) {
            v.put(f, IngestPhp.formatText(v.get(f), true));
        }
        v.put("vod_plot_name", IngestPhp.str(v.get("vod_plot_name")));
        v.put("vod_plot_detail", stringOf(v.get("vod_plot_detail")));
        if (!IngestPhp.empty(v.get("vod_plot_name"))) {
            v.put("vod_plot", 1L);
            v.put("vod_plot_name", IngestPhp.trimChars(IngestPhp.str(v.get("vod_plot_name")), "$"));
        }
        if (!IngestPhp.empty(v.get("vod_plot_detail"))) {
            v.put("vod_plot_detail", IngestPhp.trimChars(IngestPhp.str(v.get("vod_plot_detail")), "$"));
        }
        long hitsStart = IngestPhp.intval(cfg("hits_start"));
        long hitsEnd = IngestPhp.intval(cfg("hits_end"));
        if (ProvideValues.greaterThanZero(cfg("hits_start")) && ProvideValues.greaterThanZero(cfg("hits_end"))) {
            for (String f : List.of("vod_hits", "vod_hits_day", "vod_hits_week", "vod_hits_month")) {
                v.put(f, (long) rand(hitsStart, hitsEnd));
            }
        }
        if (ProvideValues.greaterThanZero(cfg("updown_start")) && !IngestPhp.empty(cfg("updown_end"))) {
            long a = IngestPhp.intval(cfg("updown_start"));
            long b = IngestPhp.intval(cfg("updown_end"));
            v.put("vod_up", (long) rand(a, b));
            v.put("vod_down", (long) rand(a, b));
        }
        if (equalsOne(cfg("score"))) {
            long num = s.random().applyAsInt(1, 1000);
            long all = num * s.random().applyAsInt(1, 10);
            v.put("vod_score_num", num);
            v.put("vod_score_all", all);
            v.put("vod_score", Math.round((double) all / num * 10) / 10.0);
        }
        if (equalsOne(cfg("psename"))) {
            v.put("vod_name", IngestPhp.repPseSyn(pseName, v.get("vod_name")));
        }
        if (equalsOne(cfg("psernd"))) {
            v.put("vod_content", IngestPhp.repPseRnd(rndWords, v.get("vod_content"), n -> s.random().applyAsInt(0, n - 1)));
        }
        if (equalsOne(cfg("psesyn"))) {
            v.put("vod_content", IngestPhp.repPseSyn(pseSyn, v.get("vod_content")));
        }
        if (equalsOne(cfg("pseplayer"))) {
            v.put("vod_play_from", IngestPhp.repPseSyn(psePlayer, v.get("vod_play_from")));
        }
        if (equalsOne(cfg("psearea"))) {
            v.put("vod_area", IngestPhp.repPseSyn(pseArea, v.get("vod_area")));
        }
        if (equalsOne(cfg("pselang"))) {
            v.put("vod_lang", IngestPhp.repPseSyn(pseLang, v.get("vod_lang")));
        }
        if (IngestPhp.empty(v.get("vod_blurb"))) {
            Object content = v.get("vod_content");
            if (content instanceof Map<?, ?>) {
                throw new Rejected("vod_content 是数组");
            }
            v.put("vod_blurb", IngestPhp.macSubstring(IngestPhp.stripTags(content), 100));
        }
    }

    /** {@code (string)$x}；数组在 PHP 里是 "Array" 并告警，旧代码随后写库报错——拒绝。 */
    private static String stringOf(Object x) {
        if (x instanceof Map<?, ?>) {
            throw new Rejected("字段是数组");
        }
        return IngestPhp.str(x);
    }

    /** 查重、新增或更新（Collect.php:570-993）。 */
    private Outcome save(Map<String, Object> v, Param param, Store store, long now) {
        String inrule = IngestPhp.str(cfg("inrule"));
        String name = IngestPhp.filterXss(v.get("vod_name"));
        String typeId = inrule.contains("b") ? IngestPhp.str(v.get("type_id")) : null;
        String year = inrule.contains("c") ? IngestPhp.str(v.get("vod_year")) : null;
        String area = inrule.contains("d") ? stringOf(v.get("vod_area")) : null;
        String lng = inrule.contains("e") ? stringOf(v.get("vod_lang")) : null;
        List<String> actorLikes = null;
        List<Long> searchIds = null;
        if (inrule.contains("f")) {
            String actor = IngestPhp.filterXss(v.get("vod_actor"));
            actorLikes = IngestPhp.likeArr(actor);
            if (s.searchEnabled()) {
                List<Long> ids = store.actorSearchIds(actor);
                searchIds = ids.isEmpty() ? List.of(0L) : ids;
            }
        }
        String director = inrule.contains("g") ? IngestPhp.filterXss(v.get("vod_director")) : null;
        if (equalsOne(cfg("tag"))) {
            // mac_get_tag 在这份代码里已改为直接返回 false（application/common.php:945-948）
            v.put("vod_tag", "");
        }
        boolean blend = actorLikes != null && director != null && !IngestPhp.empty(director);
        List<Long> blendIds = null;
        if (blend && s.searchEnabled() && searchIds.size() <= com.maccms.backend.content.application.VodSearchCache.MAX_ID_COUNT) {
            blendIds = searchIds;
        }
        VodMatch match = new VodMatch(name, typeId, year, area, lng, actorLikes, director, blend, blendIds);

        if (IngestPhp.empty(v.get("vod_play_url"))) {
            v.put("vod_play_url", "");
        }
        if (IngestPhp.empty(v.get("vod_down_url"))) {
            v.put("vod_down_url", "");
        }
        Groups play = new Groups(v, "vod_play", s.players(), param, true);
        Groups down = new Groups(v, "vod_down", s.downers(), param, false);
        play.writeAll(v);
        down.writeAll(v);

        Map<String, String> info = store.find(match);
        String vname = IngestPhp.str(v.get("vod_name"));
        if (info == null) {
            if (equalsNumber(param.opt(), 2)) {
                return red(lang("model/collect/not_check_add"), vname);
            }
            if (equalsNumber(param.filter(), 1) || equalsNumber(param.filter(), 2)) {
                play.writeFiltered(v);
                down.writeFiltered(v);
            }
            // 图片同步不实施（E02，只做本地版本）：库内地址保持提交的值，相当于 sync_pic_opt 关闭
            v.put("vod_pic", IngestPhp.str(v.get("vod_pic")));
            Map<String, Object> row = formatBeforeDb(v);
            long id = store.insert(row);
            if (id > 0) {
                if (s.searchEnabled()) {
                    Map<String, Object> withId = new LinkedHashMap<>();
                    withId.put("vod_id", id);
                    row.forEach(withId::putIfAbsent);
                    store.appendSearch(id, withId);
                }
                return new Outcome(true, lang("model/collect/add_ok"), vname);
            }
            return red("vod insert failed", vname);
        }
        if (IngestPhp.empty(cfg("uprule"))) {
            return red(lang("model/collect/uprule_empty"), vname);
        }
        if (IngestPhp.looseEquals(1L, info.get("vod_lock"))) {
            return red(lang("model/collect/data_lock"), vname);
        }
        if (equalsNumber(param.opt(), 1)) {
            return red(lang("model/collect/not_check_update"), vname);
        }
        return update(v, info, param, play, down, store, now);
    }

    private Outcome update(Map<String, Object> v, Map<String, String> info, Param param, Groups play, Groups down, Store store,
                           long now) {
        v.remove("vod_time_add");
        String uprule = "," + IngestPhp.str(cfg("uprule"));
        Map<String, Object> update = new LinkedHashMap<>();
        StringBuilder des = new StringBuilder();
        boolean[] green = {false};
        boolean useFiltered = equalsNumber(param.filter(), 1) || equalsNumber(param.filter(), 3);
        if (uprule.contains("a") && !IngestPhp.empty(v.get("vod_play_from"))) {
            mergeGroups(useFiltered ? play.filtered : play.kept, info, "vod_play", "playurl_same", "playfrom_empty", "playgroup",
                    update, des, green);
        }
        if (uprule.contains("b") && !IngestPhp.empty(v.get("vod_down_from"))) {
            mergeGroups(useFiltered ? down.filtered : down.kept, info, "vod_down", "downurl_same", "downfrom_empty", "downgroup",
                    update, des, green);
        }
        Object serial = v.get("vod_serial");
        if (uprule.contains("c") && !IngestPhp.empty(serial) && !IngestPhp.looseEquals(serial, info.get("vod_serial"))) {
            Object value = serial;
            String old = info.get("vod_serial");
            // 连载数都是整数时取较大值（旧版 floor($x) == $x；库里的值不是数字串时 PHP 8 报错、PHP 7 按非整数处理，新版按后者，D-B19-4）
            if (old != null && IngestPhp.isNumeric(old)) {
                double o = Double.parseDouble(old.strip());
                if (Math.floor(o) == o && o > IngestPhp.intval(serial)) {
                    value = old;
                }
            }
            update.put("vod_serial", value);
        }
        changed(v, info, update, uprule, 'd', "vod_remarks");
        changed(v, info, update, uprule, 'e', "vod_director");
        changed(v, info, update, uprule, 'f', "vod_actor");
        changed(v, info, update, uprule, 'g', "vod_year");
        changed(v, info, update, uprule, 'h', "vod_area");
        changed(v, info, update, uprule, 'i', "vod_lang");
        if (uprule.contains("j")) {
            String oldPic = info.get("vod_pic");
            boolean remote = oldPic != null && oldPic.length() >= 4 && oldPic.startsWith("http") || oldPic == null || oldPic.isEmpty()
                    || oldPic.equals("0");
            if (remote && !IngestPhp.looseEquals(v.get("vod_pic"), oldPic)) {
                update.put("vod_pic", IngestPhp.str(v.get("vod_pic")));
            }
        }
        changed(v, info, update, uprule, 'k', "vod_content");
        changed(v, info, update, uprule, 'l', "vod_tag");
        changed(v, info, update, uprule, 'm', "vod_sub");
        if (uprule.contains("n") && !IngestPhp.empty(v.get("vod_class")) && !IngestPhp.looseEquals(v.get("vod_class"),
                info.get("vod_class"))) {
            update.put("vod_class", txtMerge(info.get("vod_class"), v.get("vod_class")));
        }
        changed(v, info, update, uprule, 'o', "vod_writer");
        changed(v, info, update, uprule, 'p', "vod_version");
        changed(v, info, update, uprule, 'q', "vod_state");
        changed(v, info, update, uprule, 'r', "vod_blurb");
        changed(v, info, update, uprule, 's', "vod_tv");
        changed(v, info, update, uprule, 't', "vod_weekday");
        changed(v, info, update, uprule, 'u', "vod_total");
        changed(v, info, update, uprule, 'v', "vod_isend");
        if (uprule.contains("w") && !IngestPhp.empty(v.get("vod_plot_name")) && !IngestPhp.looseEquals(v.get("vod_plot_name"),
                info.get("vod_plot_name"))) {
            update.put("vod_plot", 1L);
            update.put("vod_plot_name", v.get("vod_plot_name"));
            update.put("vod_plot_detail", v.get("vod_plot_detail"));
        }
        String vname = IngestPhp.str(v.get("vod_name"));
        if (update.isEmpty()) {
            return new Outcome(green[0], lang("model/collect/not_need_update"), vname);
        }
        update.put("vod_time", now);
        store.update(Long.parseLong(info.get("vod_id")), formatBeforeDb(update));
        return new Outcome(true, des.toString(), vname);
    }

    /** {@code if (strpos(',' . uprule, x) !== false && !empty($v[f]) && $v[f] != $info[f]) $update[f] = $v[f]}。 */
    private static void changed(Map<String, Object> v, Map<String, String> info, Map<String, Object> update, String uprule, char rule,
                                String f) {
        Object x = v.get(f);
        if (uprule.indexOf(rule) >= 0 && !IngestPhp.empty(x) && !IngestPhp.looseEquals(x, info.get(f))) {
            update.put(f, x);
        }
    }

    /**
     * 播放组或下载组的合并（Collect.php:833-961）。{@code groups} 是采集到的组（键为原位置），{@code pre} 为 {@code vod_play} 或
     * {@code vod_down}；新类型的组追加在末尾，同类型的组按 {@code urlrole} 替换或合并去重。
     */
    private void mergeGroups(Group groups, Map<String, String> info, String pre, String sameKey, String emptyKey, String groupKey,
                             Map<String, Object> update, StringBuilder des, boolean[] green) {
        if (groups == null) {
            return;
        }
        String infoFrom = nz(info.get(pre + "_from"));
        String infoUrl = info.get(pre + "_url");
        String oldFrom = infoFrom;
        String oldUrl = nz(infoUrl);
        String oldServer = nz(info.get(pre + "_server"));
        String oldNote = nz(info.get(pre + "_note"));
        boolean ec = false;
        for (Integer k : groups.from.keySet()) {
            String from = groups.from.get(k);
            String url = groups.url.get(k);
            String server = groups.server.get(k);
            String note = groups.note.get(k);
            if (IngestPhp.looseEquals(url, infoUrl)) {
                des.append(lang("model/collect/" + sameKey));
            } else if (IngestPhp.empty(from)) {
                des.append(lang("model/collect/" + emptyKey));
            } else if (!("$$$" + infoFrom + "$$$").contains("$$$" + from + "$$$")) {
                green[0] = true;
                des.append(lang("model/collect/" + groupKey + "_add_ok", from));
                if (!IngestPhp.empty(oldFrom)) {
                    oldUrl += "$$$";
                    oldFrom += "$$$";
                    oldServer += "$$$";
                    oldNote += "$$$";
                }
                oldUrl += nz(url);
                oldFrom += from;
                oldServer += nz(server);
                oldNote += nz(note);
                ec = true;
            } else if (!IngestPhp.empty(url)) {
                // 旧版的 $arr1：可能比 $arr2 短，按键写入时追加在末尾
                LinkedHashMap<Integer, String> arr1 = new LinkedHashMap<>();
                List<String> u = IngestPhp.explode("$$$", oldUrl);
                for (int i = 0; i < u.size(); i++) {
                    arr1.put(i, u.get(i));
                }
                List<String> arr2 = IngestPhp.explode("$$$", oldFrom);
                int key = 0;
                for (int i = 0; i < arr2.size(); i++) {
                    if (IngestPhp.looseEquals(from, arr2.get(i))) {
                        key = i;
                        break;
                    }
                }
                String current = arr1.get(key);
                if (current != null && IngestPhp.looseEquals(url, current)) {
                    des.append(lang("model/collect/" + groupKey + "_same", from));
                } else {
                    green[0] = true;
                    des.append(lang("model/collect/" + groupKey + "_update_ok", from));
                    String merged = url;
                    if (equalsOne(cfg("urlrole"))) {
                        LinkedHashSet<String> parts = new LinkedHashSet<>(IngestPhp.explode("#", nz(current)));
                        parts.addAll(IngestPhp.explode("#", url));
                        merged = String.join("#", parts);
                    }
                    arr1.put(key, merged);
                    ec = true;
                }
                oldUrl = String.join("$$$", arr1.values());
            }
        }
        if (ec) {
            update.put(pre + "_from", oldFrom);
            update.put(pre + "_url", oldUrl);
            update.put(pre + "_server", oldServer);
            update.put(pre + "_note", oldNote);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 采集到的一种组（四列，键为 {@code explode('$$$')} 后的原位置，保持旧版数组的插入顺序）。 */
    static final class Group {
        final LinkedHashMap<Integer, String> from = new LinkedHashMap<>();
        final LinkedHashMap<Integer, String> url = new LinkedHashMap<>();
        final LinkedHashMap<Integer, String> server = new LinkedHashMap<>();
        final LinkedHashMap<Integer, String> note = new LinkedHashMap<>();
    }

    /**
     * 播放组或下载组的整理（Collect.php:621-691）：四列各按 {@code $$$} 拆开；类型为空或不在播放器（下载器）配置里的组整组去掉；地址去掉末尾的
     * {@code #}（下载组去掉末尾空白）；{@code filter > 0} 时类型出现在 {@code ,filter_from,} 里（子串匹配，同旧版）的组另记一份。
     */
    private static final class Groups {
        final String pre;
        final Group kept;
        /** 旧 {@code $collect_filter[...][$param['filter']]}；一组都没有时为 null（旧版是未定义的下标）。 */
        Group filtered;

        Groups(Map<String, Object> v, String pre, Map<Object, Object> allowed, Param param, boolean play) {
            this.pre = pre;
            this.kept = new Group();
            List<String> from = IngestPhp.explode("$$$", stringOf(v.get(pre + "_from")));
            List<String> url = IngestPhp.explode("$$$", stringOf(v.get(pre + "_url")));
            List<String> server = IngestPhp.explode("$$$", stringOf(v.get(pre + "_server")));
            List<String> note = IngestPhp.explode("$$$", stringOf(v.get(pre + "_note")));
            LinkedHashMap<Integer, String> u = indexed(url);
            LinkedHashMap<Integer, String> sv = indexed(server);
            LinkedHashMap<Integer, String> nt = indexed(note);
            boolean filter = ProvideValues.greaterThanZero(param.filter());
            String filterFrom = "," + IngestPhp.str(param.filterFrom()) + ",";
            for (int k = 0; k < from.size(); k++) {
                String f = from.get(k);
                if (IngestPhp.empty(f) || IngestPhp.empty(ProvideValues.get(allowed, f))) {
                    u.remove(k);
                    sv.remove(k);
                    nt.remove(k);
                    continue;
                }
                String x = nz(u.get(k));
                u.put(k, play ? IngestPhp.rtrimChars(x, "#") : IngestPhp.rtrim(x));
                sv.put(k, nz(sv.get(k)));
                nt.put(k, nz(nt.get(k)));
                kept.from.put(k, f);
                if (filter && filterFrom.contains(f)) {
                    if (filtered == null) {
                        filtered = new Group();
                    }
                    filtered.from.put(k, f);
                    filtered.url.put(k, u.get(k));
                    filtered.server.put(k, sv.get(k));
                    filtered.note.put(k, nt.get(k));
                }
            }
            kept.url.putAll(u);
            kept.server.putAll(sv);
            kept.note.putAll(nt);
        }

        private static LinkedHashMap<Integer, String> indexed(List<String> l) {
            LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
            for (int i = 0; i < l.size(); i++) {
                m.put(i, l.get(i));
            }
            return m;
        }

        /** 四列写回 {@code $v}（{@code join('$$$', ...)}，按插入顺序）。 */
        void writeAll(Map<String, Object> v) {
            write(v, kept);
        }

        /** 新增时 {@code filter} 为 1 或 2：只写另记的那份（没有时四列为空串，D-B19-5）。 */
        void writeFiltered(Map<String, Object> v) {
            write(v, filtered == null ? new Group() : filtered);
        }

        private void write(Map<String, Object> v, Group g) {
            v.put(pre + "_from", String.join("$$$", g.from.values()));
            v.put(pre + "_url", String.join("$$$", g.url.values()));
            v.put(pre + "_server", String.join("$$$", g.server.values()));
            v.put(pre + "_note", String.join("$$$", g.note.values()));
        }
    }

    /** 旧 {@code VodValidate::formatDataBeforeDb}：列出的字段（非 null）{@code mac_filter_xss} 后按字符数截断；其余原样。 */
    static Map<String, Object> formatBeforeDb(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>(data);
        for (Map.Entry<String, Integer> e : FORMAT_FIELDS.entrySet()) {
            Object x = out.get(e.getKey());
            if (x == null) {
                continue;
            }
            if (x instanceof Map<?, ?>) {
                throw new Rejected("字段是数组");
            }
            out.put(e.getKey(), IngestPhp.mbSubstr(IngestPhp.filterXss(x), e.getValue()));
        }
        return out;
    }

    private Object txtMerge(Object txt, Object str) {
        return IngestPhp.txtMerge(txt, str, !Objects.equals(IngestPhp.str(cfg("class_filter")), "0"), lang("slice"), lang("drama"));
    }

    private int rand(long a, long b) {
        return s.random().applyAsInt((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, a)),
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, b)));
    }

    private Object cfg(String k) {
        return ProvideValues.get(s.config(), k);
    }

    private String c(String k) {
        return IngestPhp.str(cfg(k));
    }

    /** PHP 8 {@code $x == 1}。 */
    static boolean equalsOne(Object x) {
        return equalsNumber(x, 1);
    }

    /** PHP 8 {@code $x == n}（null 与 false 只等于 0）。 */
    static boolean equalsNumber(Object x, long n) {
        return switch (x) {
            case null -> n == 0;
            case Long l -> l == n;
            case Double d -> d == n;
            case Boolean b -> b == (n != 0);
            case String str -> IngestPhp.isNumeric(str) ? Double.parseDouble(str.strip()) == n : str.equals(Long.toString(n));
            default -> false;
        };
    }

    private String lang(String key, String... args) {
        String t = s.lang().apply(key);
        for (String a : args) {
            int i = t.indexOf("%s");
            if (i < 0) {
                break;
            }
            t = t.substring(0, i) + a + t.substring(i + 2);
        }
        return t;
    }

    private static Outcome red(String des, String name) {
        return new Outcome(false, des, name);
    }

    private static Map<String, Integer> formatFields() {
        Map<String, Integer> m = new LinkedHashMap<>();
        Object[][] list = {{"vod_name", 255}, {"vod_sub", 255}, {"vod_en", 255}, {"vod_color", 6}, {"vod_tag", 100},
            {"vod_class", 255}, {"vod_pic", 1024}, {"vod_pic_thumb", 1024}, {"vod_pic_slide", 1024},
            {"vod_pic_screenshot", 65535}, {"vod_actor", 255}, {"vod_director", 255}, {"vod_writer", 100},
            {"vod_behind", 100}, {"vod_blurb", 255}, {"vod_remarks", 100}, {"vod_pubdate", 100}, {"vod_serial", 20},
            {"vod_tv", 30}, {"vod_weekday", 30}, {"vod_area", 20}, {"vod_lang", 10}, {"vod_year", 10}, {"vod_version", 30},
            {"vod_state", 30}, {"vod_author", 60}, {"vod_jumpurl", 150}, {"vod_tpl", 30}, {"vod_tpl_play", 30},
            {"vod_tpl_down", 30}, {"vod_duration", 10}, {"vod_reurl", 255}, {"vod_rel_vod", 255}, {"vod_rel_art", 255},
            {"vod_pwd", 10}, {"vod_pwd_url", 255}, {"vod_pwd_play", 10}, {"vod_pwd_play_url", 255}, {"vod_pwd_down", 10},
            {"vod_pwd_down_url", 255}, {"vod_play_from", 255}, {"vod_play_server", 255}, {"vod_play_note", 255},
            {"vod_down_from", 255}, {"vod_down_server", 255}, {"vod_down_note", 255}};
        for (Object[] e : list) {
            m.put((String) e[0], (Integer) e[1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
