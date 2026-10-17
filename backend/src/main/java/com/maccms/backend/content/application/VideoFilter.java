package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * GET /api/v1/videos 的搜索/筛选条件（契约 1.1.0 起，规则见 docs/architecture/09-content-search-filter-plan.md 第 4 节；
 * 1.3.0 / B04 新增的参数见 docs/architecture/14-video-list-filter-completion.md 第 3 节）。
 * 字段为 {@code null} 表示该条件未设置；{@link #NONE} 表示不带任何新条件，此时列表查询与 1.0.0 完全相同。
 *
 * <p>校验对象是容器解码一次后的原始值，不裁剪、不改写；只有空字符串按未传。紧凑构造器与 {@link #parse} 使用同一套规则
 * （其他模块直接构造也不能越过），但"参数重复""非法 UTF-8"只在 HTTP 层可见、"搜索开关"是运行配置，这三项只由
 * {@link #parse} 判断；开关另由 {@link ContentReadService} 在查询前再次检查。
 *
 * <p>匹配规则：keyword 在 vod_name/vod_sub/vod_en 中包含匹配（旧 wd，application/common/model/Vod.php:462-468），
 * classes 在 vod_class 中包含匹配（旧 class，Vod.php:475-477），area/lang/level 精确匹配（Vod.php:290-304），
 * year 为 4 位单值或 9 字符区间（Vod.php:183-201，区间两端颠倒时交换后展开）。
 * 1.3.0 起 area、lang、classes 与旧版一样按半角逗号分多值（保留空项）：area/lang 精确匹配任一项（{@code IN explode}），
 * classes 包含匹配任一项（{@code mac_like_arr}）；不含逗号时与 1.1.0 相同。
 * 1.3.0 新增参数沿用旧版的逗号多值（{@code explode(',')}，保留空项）：letter（Vod.php:330-335，含 {@code 0?9} 展开）、
 * state、version（:305-310）精确匹配任一项；actor、director、tag、name（:469-483，{@code mac_like_arr}，
 * application/common.php:1284-1292）包含匹配任一项；ids（:273-277）为 {@code all}（不筛选）或逗号分隔的视频 ID。
 * 包含匹配的 LIKE 模式中 {@code %}、{@code _}、{@code !} 以 {@code !} 转义（SQL 使用 {@code ESCAPE '!'}），按字面匹配。
 */
public record VideoFilter(String keyword, String area, String lang, String year, Integer level, String classes,
                          String letter, String state, String version, String actor, String director, String tag,
                          String name, String ids) {

    public static final VideoFilter NONE = new VideoFilter(null, null, null, null, null, null);

    public static final int MAX_KEYWORD_LENGTH = 64;
    /** 列宽 varchar(20)（application/install/sql/install.sql:713）。 */
    public static final int MAX_AREA_LENGTH = 20;
    /** 列宽 varchar(10)（application/install/sql/install.sql:714）。 */
    public static final int MAX_LANG_LENGTH = 10;
    public static final int MAX_CLASSES_LENGTH = 32;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 9;
    /** 1.3.0 逗号多值参数（letter/state/version/actor/director/tag/name）整个原始值的码点上限。 */
    public static final int MAX_LIST_LENGTH = 64;
    /** ids 最多的项数（含空项）。旧版不限；与旧 VodSearch 的 maxIdCount=1000（缓存阈值）无关。 */
    public static final int MAX_IDS = 100;
    public static final long MAX_VIDEO_ID = VideoId.MAX;
    /** ids 的"不筛选"写法（旧 Vod.php:274）。 */
    public static final String ALL_IDS = "all";

    static final String DUPLICATE = "参数不能重复";
    static final String SEARCH_DISABLED = "当前未开放关键字搜索";
    static final String BAD_CHARACTER = "含不允许的字符";
    /** 1.2.0 其他列表的精确筛选参数（{@link ContentFilter}）仍为单值；视频列表 1.3.0 起不再产生这一文案。 */
    static final String MULTI_VALUE = "不支持多个取值";
    static final String SURROUNDING_WHITESPACE = "首尾不能有空白字符";
    static final String BAD_YEAR = "必须是 4 位数字年份";
    static final String BAD_IDS = "必须是 all 或以逗号分隔的视频 ID";
    static final String TOO_MANY_IDS = "最多 " + MAX_IDS + " 个 ID";

    /** 参数的校验顺序（只报第一个错误）。 */
    public static final List<String> PARAMETERS = List.of("keyword", "area", "lang", "year", "level", "classes",
            "letter", "state", "version", "actor", "director", "tag", "name", "ids");

    private static final List<String> DIGIT_LETTERS = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

    public VideoFilter {
        keyword = text("keyword", keyword, MAX_KEYWORD_LENGTH, true);
        area = text("area", area, MAX_AREA_LENGTH, true);
        lang = text("lang", lang, MAX_LANG_LENGTH, true);
        year = year(year);
        if (level != null && (level < MIN_LEVEL || level > MAX_LEVEL)) {
            throw levelError();
        }
        classes = text("classes", classes, MAX_CLASSES_LENGTH, true);
        letter = text("letter", letter, MAX_LIST_LENGTH, true);
        state = text("state", state, MAX_LIST_LENGTH, true);
        version = text("version", version, MAX_LIST_LENGTH, true);
        actor = text("actor", actor, MAX_LIST_LENGTH, true);
        director = text("director", director, MAX_LIST_LENGTH, true);
        tag = text("tag", tag, MAX_LIST_LENGTH, true);
        name = text("name", name, MAX_LIST_LENGTH, true);
        ids = ids(ids);
    }

    /** 契约 1.1.0 的 6 个条件（其余为未设置）。 */
    public VideoFilter(String keyword, String area, String lang, String year, Integer level, String classes) {
        this(keyword, area, lang, year, level, classes, null, null, null, null, null, null, null, null);
    }

    /**
     * 一个查询参数在请求中的原始情况，由 api 层从容器参数与原始查询串得到。
     *
     * @param occurrences 出现次数（同名参数，含空值）
     * @param value       容器解码一次后的值；未出现或无法解码时为 {@code null}
     * @param malformed   原始查询串中该参数的百分号编码不合法或不是合法 UTF-8
     */
    public record RawParam(int occurrences, String value, boolean malformed) {

        public static final RawParam ABSENT = new RawParam(0, null, false);

        public static RawParam of(String value) {
            return value == null ? ABSENT : new RawParam(1, value, false);
        }
    }

    /** 单值形式（每个参数最多出现一次、编码合法），供测试与其他调用方使用。 */
    public static VideoFilter parse(String keyword, String area, String lang, String year, String level, String classes,
                                    boolean searchEnabled) {
        return parse(RawParam.of(keyword), RawParam.of(area), RawParam.of(lang), RawParam.of(year), RawParam.of(level),
                RawParam.of(classes), searchEnabled);
    }

    /** 契约 1.1.0 的 6 个参数（其余按未传）。 */
    public static VideoFilter parse(RawParam keyword, RawParam area, RawParam lang, RawParam year, RawParam level,
                                    RawParam classes, boolean searchEnabled) {
        List<RawParam> six = List.of(keyword, area, lang, year, level, classes);
        return parse(p -> {
            int i = PARAMETERS.indexOf(p);
            return i < six.size() ? six.get(i) : RawParam.ABSENT;
        }, searchEnabled);
    }

    /**
     * 按 {@link #PARAMETERS} 的固定顺序读取并校验；{@code param} 返回参数的原始情况（不得为 {@code null}）。
     * 每个参数内：重复 → 空串（未传）→ 开关（包含匹配参数）→ 禁用字符/非法 UTF-8 → 首尾空白 → 长度 → 格式（year、level、ids）。
     * 只报第一个错误。
     */
    public static VideoFilter parse(Function<String, RawParam> param, boolean searchEnabled) {
        String k = rawText("keyword", param.apply("keyword"), MAX_KEYWORD_LENGTH, true, searchEnabled);
        String a = rawText("area", param.apply("area"), MAX_AREA_LENGTH, true, true);
        String l = rawText("lang", param.apply("lang"), MAX_LANG_LENGTH, true, true);
        RawParam year = param.apply("year");
        String y = present("year", year) ? (year.malformed() ? fail("year", BAD_YEAR) : year(year.value())) : null;
        Integer lv = null;
        RawParam level = param.apply("level");
        if (present("level", level)) {
            if (level.malformed()) {
                throw levelError();
            }
            lv = VideoListQuery.parseInt("level", level.value(), MIN_LEVEL, MAX_LEVEL);
        }
        String c = rawText("classes", param.apply("classes"), MAX_CLASSES_LENGTH, true, searchEnabled);
        String letter = rawText("letter", param.apply("letter"), MAX_LIST_LENGTH, true, true);
        String state = rawText("state", param.apply("state"), MAX_LIST_LENGTH, true, true);
        String version = rawText("version", param.apply("version"), MAX_LIST_LENGTH, true, true);
        String actor = rawText("actor", param.apply("actor"), MAX_LIST_LENGTH, true, searchEnabled);
        String director = rawText("director", param.apply("director"), MAX_LIST_LENGTH, true, searchEnabled);
        String tag = rawText("tag", param.apply("tag"), MAX_LIST_LENGTH, true, searchEnabled);
        String name = rawText("name", param.apply("name"), MAX_LIST_LENGTH, true, searchEnabled);
        RawParam ids = param.apply("ids");
        String i = null;
        if (present("ids", ids)) {
            if (ids.malformed()) {
                throw ApiException.invalidParameter("ids", BAD_CHARACTER);
            }
            i = ids(ids.value());
        }
        return new VideoFilter(k, a, l, y, lv, c, letter, state, version, actor, director, tag, name, i);
    }

    /** 不带任何查询条件（列表查询与 1.0.0 相同）。{@code ids=all} 不产生条件（旧版同样不筛选）。 */
    public boolean isEmpty() {
        return keyword == null && area == null && lang == null && year == null && level == null && classes == null
                && letter == null && state == null && version == null && !usesListContainsMatch()
                && (ids == null || ALL_IDS.equals(ids));
    }

    /** 带包含匹配条件（受搜索开关与并发上限约束）：keyword、classes、actor、director、tag、name。 */
    public boolean usesContainsMatch() {
        return keyword != null || classes != null || usesListContainsMatch();
    }

    /** 第一个设置了的包含匹配参数名（按校验顺序），没有时为 {@code null}。用于开关关闭时的错误字段。 */
    public String firstContainsMatchParameter() {
        String[] names = {"keyword", "classes", "actor", "director", "tag", "name"};
        String[] values = {keyword, classes, actor, director, tag, name};
        for (int i = 0; i < names.length; i++) {
            if (values[i] != null) {
                return names[i];
            }
        }
        return null;
    }

    private boolean usesListContainsMatch() {
        return actor != null || director != null || tag != null || name != null;
    }

    /**
     * keyword 的 LIKE 模式：原值一项；含 {@code & " ' < >} 时另加 mac_filter_xss 形式
     * （application/common.php:1222-1225，htmlspecialchars ENT_QUOTES）一项，两项之间为 OR。未设置时为 {@code null}。
     */
    public List<String> keywordPatterns() {
        return keyword == null ? null : withEntityForm(keyword);
    }

    /**
     * classes 为单值（不含半角逗号）时的 LIKE 模式（不做实体变体）；未设置或多值时为 {@code null}，多值见
     * {@link #classesPatterns()}。
     */
    public String classesPattern() {
        return classes == null || classes.indexOf(',') >= 0 ? null : contains(classes);
    }

    /**
     * classes 按半角逗号拆分后各项的 LIKE 模式（保留空项，空项为 {@code %%}、命中全部，与旧 mac_like_arr 相同；不做实体变体），
     * 单值时只有一项。未设置时为 {@code null}。
     */
    public List<String> classesPatterns() {
        return classes == null ? null : items(classes).stream().map(VideoFilter::contains).toList();
    }

    /** area 按半角逗号拆分的各项（保留空项，空项命中地区为空的行），单值时只有一项。未设置时为 {@code null}。 */
    public List<String> areas() {
        return area == null ? null : items(area);
    }

    /** lang 按半角逗号拆分的各项（保留空项），单值时只有一项。未设置时为 {@code null}。 */
    public List<String> langs() {
        return lang == null ? null : items(lang);
    }

    /**
     * year 展开后的取值（字符串比较）：4 位单值为它本身；区间取前后两段 4 位数字，前大后小时交换（旧 Vod.php:187-200），
     * 逐年展开为 4 位（不足补 0）。未设置时为 {@code null}。最多 10000 项（{@code 0000-9999}）。
     */
    public List<String> years() {
        if (year == null) {
            return null;
        }
        if (year.length() == 4) {
            return List.of(year);
        }
        int s = Integer.parseInt(year.substring(0, 4));
        int e = Integer.parseInt(year.substring(5, 9));
        if (s > e) {
            int t = s;
            s = e;
            e = t;
        }
        List<String> out = new ArrayList<>(e - s + 1);
        for (int y = s; y <= e; y++) {
            out.add(String.format("%04d", y));
        }
        return out;
    }

    /**
     * letter 的取值：首字节为 {@code 0} 且第 3 个字节为 {@code 9}（如 {@code 0-9}、{@code 0x9}、{@code 0,9}）时为 0..9
     * 十个数字（旧 Vod.php:331-333，按 PHP substr 的字节位置，在 htmlspecialchars 之后的形式上判断）；否则按半角逗号拆分，
     * 保留空项。未设置时为 {@code null}。
     */
    public List<String> letters() {
        if (letter == null) {
            return null;
        }
        byte[] b = htmlEntities(letter).getBytes(StandardCharsets.UTF_8);
        if (b.length >= 3 && b[0] == '0' && b[2] == '9') {
            return DIGIT_LETTERS;
        }
        return items(letter);
    }

    /** state 按半角逗号拆分的各项（保留空项），未设置时为 {@code null}。 */
    public List<String> states() {
        return state == null ? null : items(state);
    }

    /** version 按半角逗号拆分的各项（保留空项），未设置时为 {@code null}。 */
    public List<String> versions() {
        return version == null ? null : items(version);
    }

    /** actor 各项的 LIKE 模式（空项为 {@code %%}，与旧 mac_like_arr 相同，命中全部），未设置时为 {@code null}。 */
    public List<String> actorPatterns() {
        return listPatterns(actor);
    }

    public List<String> directorPatterns() {
        return listPatterns(director);
    }

    public List<String> tagPatterns() {
        return listPatterns(tag);
    }

    public List<String> namePatterns() {
        return listPatterns(name);
    }

    /**
     * ids 的取值：未设置或 {@code all} 时为 {@code null}（不筛选）；否则为各项的 ID，空项按 0（旧版 {@code vod_id IN ('')}
     * 中的空串按数值 0 比较，命中不了任何视频）。
     */
    public List<Long> idList() {
        if (ids == null || ALL_IDS.equals(ids)) {
            return null;
        }
        return items(ids).stream().map(s -> s.isEmpty() ? 0L : Long.parseLong(s)).toList();
    }

    // ------------------------------------------------------------------ 规则

    private static boolean present(String field, RawParam p) {
        if (p.occurrences() >= 2) {
            throw ApiException.invalidParameter(field, DUPLICATE);
        }
        if (p.occurrences() == 0) {
            return false;
        }
        return p.malformed() || (p.value() != null && !p.value().isEmpty());
    }

    private static String rawText(String field, RawParam p, int max, boolean commaAllowed, boolean enabled) {
        if (!present(field, p)) {
            return null;
        }
        if (!enabled) {
            throw ApiException.invalidParameter(field, SEARCH_DISABLED);
        }
        if (p.malformed()) {
            throw ApiException.invalidParameter(field, BAD_CHARACTER);
        }
        return text(field, p.value(), max, commaAllowed);
    }

    /** 步骤 4–7。{@code null} 与空串都表示未设置。契约 1.2.0 的精确筛选参数（{@link ContentFilter}）复用同一规则。 */
    static String text(String field, String v, int max, boolean commaAllowed) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            if (forbidden(v.charAt(i))) {
                throw ApiException.invalidParameter(field, BAD_CHARACTER);
            }
        }
        if (!commaAllowed && v.indexOf(',') >= 0) {
            throw ApiException.invalidParameter(field, MULTI_VALUE);
        }
        if (whitespace(v.charAt(0)) || whitespace(v.charAt(v.length() - 1))) {
            throw ApiException.invalidParameter(field, SURROUNDING_WHITESPACE);
        }
        // 代理项已被拒绝，码点数等于 String.length()
        if (v.length() > max) {
            throw ApiException.invalidParameter(field, "长度不能超过 " + max + " 个字符");
        }
        return v;
    }

    /**
     * 4 位 ASCII 数字；或 9 个字符：前 4 位、后 4 位为 ASCII 数字，第 5 个字符为任一可打印 ASCII 字符（旧版 strlen==9 时
     * 只取 substr(0,4) 与 substr(5,4)，中间一个字节不检查）。
     */
    private static String year(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        boolean single = v.length() == 4 && digits(v, 0, 4);
        boolean range = v.length() == 9 && digits(v, 0, 4) && digits(v, 5, 9) && v.charAt(4) >= 0x20
                && v.charAt(4) <= 0x7E;
        if (!single && !range) {
            throw ApiException.invalidParameter("year", BAD_YEAR);
        }
        return v;
    }

    /** {@code all}，或最多 {@link #MAX_IDS} 项、每项为空或 1..10 位 ASCII 数字且不超过 {@link #MAX_VIDEO_ID}。 */
    private static String ids(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            if (forbidden(v.charAt(i))) {
                throw ApiException.invalidParameter("ids", BAD_CHARACTER);
            }
        }
        if (whitespace(v.charAt(0)) || whitespace(v.charAt(v.length() - 1))) {
            throw ApiException.invalidParameter("ids", SURROUNDING_WHITESPACE);
        }
        if (ALL_IDS.equals(v)) {
            return v;
        }
        List<String> items = items(v);
        if (items.size() > MAX_IDS) {
            throw ApiException.invalidParameter("ids", TOO_MANY_IDS);
        }
        for (String s : items) {
            if (!s.isEmpty() && (s.length() > 10 || !digits(s, 0, s.length()) || Long.parseLong(s) > MAX_VIDEO_ID)) {
                throw ApiException.invalidParameter("ids", BAD_IDS);
            }
        }
        return v;
    }

    private static boolean digits(String v, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 与 PHP {@code explode(',', $v)} 相同：保留空项（含首尾）。 */
    static List<String> items(String v) {
        return Arrays.asList(v.split(",", -1));
    }

    private static List<String> listPatterns(String v) {
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String item : items(v)) {
            out.addAll(withEntityForm(item));
        }
        return List.copyOf(out);
    }

    /** 原值的包含模式；含 {@code & " ' < >} 时另加 htmlspecialchars(ENT_QUOTES) 形式的包含模式。 */
    private static List<String> withEntityForm(String v) {
        String entities = htmlEntities(v);
        return entities.equals(v) ? List.of(contains(v)) : List.of(contains(v), contains(entities));
    }

    private static ApiException levelError() {
        return ApiException.invalidParameter("level", "必须是 " + MIN_LEVEL + " 到 " + MAX_LEVEL + " 之间的整数");
    }

    private static String fail(String field, String message) {
        throw ApiException.invalidParameter(field, message);
    }

    /** C0/C1 控制字符、代理项（U+FFFF 以上字符或非法 UTF-8 的残留）、U+FFFD。 */
    static boolean forbidden(char c) {
        return c <= 0x1F || (c >= 0x7F && c <= 0x9F) || Character.isSurrogate(c) || c == '�';
    }

    /** 与 ECMAScript {@code \s} 相同的空白集合（不用 trim/strip/isWhitespace，三者集合都不同）。 */
    static boolean whitespace(char c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /** '%' + 转义 + '%'，转义符为 '!'：依次给 {@code ! % _} 前加 '!'。反斜线不是转义符。 */
    static String contains(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8).append('%');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /** htmlspecialchars(ENT_QUOTES) 的五个替换，{@code &} 先替换（输入中已有的实体会被再编码一次，与 PHP 相同）。 */
    static String htmlEntities(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 16);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#039;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
