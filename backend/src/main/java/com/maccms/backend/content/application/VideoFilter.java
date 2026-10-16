package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import java.util.List;

/**
 * GET /api/v1/videos 的搜索/筛选条件（契约 1.1.0，规则见 docs/architecture/09-content-search-filter-plan.md 第 4 节）。
 * 字段为 {@code null} 表示该条件未设置；{@link #NONE} 表示不带任何新条件，此时列表查询与 1.0.0 完全相同。
 *
 * <p>校验对象是容器解码一次后的原始值，不裁剪、不改写；只有空字符串按未传。紧凑构造器与 {@link #parse} 使用同一套规则
 * （其他模块直接构造也不能越过），但"参数重复""非法 UTF-8"只在 HTTP 层可见、"搜索开关"是运行配置，这三项只由
 * {@link #parse} 判断；开关另由 {@link ContentReadService} 在查询前再次检查。
 *
 * <p>匹配规则：keyword 在 vod_name/vod_sub/vod_en 中包含匹配（旧 wd，application/common/model/Vod.php:462-468），
 * classes 在 vod_class 中包含匹配（旧 class，Vod.php:475-477），area/lang/year/level 精确匹配（Vod.php:290-304）。
 * 包含匹配的 LIKE 模式由 {@link #keywordPatterns()}、{@link #classesPattern()} 构造，{@code %}、{@code _}、{@code !}
 * 以 {@code !} 转义（SQL 使用 {@code ESCAPE '!'}），按字面匹配。
 */
public record VideoFilter(String keyword, String area, String lang, String year, Integer level, String classes) {

    public static final VideoFilter NONE = new VideoFilter(null, null, null, null, null, null);

    public static final int MAX_KEYWORD_LENGTH = 64;
    /** 列宽 varchar(20)（application/install/sql/install.sql:713）。 */
    public static final int MAX_AREA_LENGTH = 20;
    /** 列宽 varchar(10)（application/install/sql/install.sql:714）。 */
    public static final int MAX_LANG_LENGTH = 10;
    public static final int MAX_CLASSES_LENGTH = 32;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 9;

    static final String DUPLICATE = "参数不能重复";
    static final String SEARCH_DISABLED = "当前未开放关键字搜索";
    static final String BAD_CHARACTER = "含不允许的字符";
    static final String MULTI_VALUE = "不支持多个取值";
    static final String SURROUNDING_WHITESPACE = "首尾不能有空白字符";
    static final String BAD_YEAR = "必须是 4 位数字年份";

    public VideoFilter {
        keyword = text("keyword", keyword, MAX_KEYWORD_LENGTH, true);
        area = text("area", area, MAX_AREA_LENGTH, false);
        lang = text("lang", lang, MAX_LANG_LENGTH, false);
        year = year(year);
        if (level != null && (level < MIN_LEVEL || level > MAX_LEVEL)) {
            throw levelError();
        }
        classes = text("classes", classes, MAX_CLASSES_LENGTH, false);
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

    /**
     * 按 09 方案第 4.3 节的固定顺序校验：参数之间 keyword → area → lang → year → level → classes；
     * 每个参数内 重复 → 空串（未传）→ 开关 → 禁用字符/非法 UTF-8 → 逗号 → 首尾空白 → 长度 → 格式。只报第一个错误。
     */
    public static VideoFilter parse(RawParam keyword, RawParam area, RawParam lang, RawParam year, RawParam level,
                                    RawParam classes, boolean searchEnabled) {
        String k = rawText("keyword", keyword, MAX_KEYWORD_LENGTH, true, searchEnabled);
        String a = rawText("area", area, MAX_AREA_LENGTH, false, true);
        String l = rawText("lang", lang, MAX_LANG_LENGTH, false, true);
        String y = present("year", year) ? (year.malformed() ? fail("year", BAD_YEAR) : year(year.value())) : null;
        Integer lv = null;
        if (present("level", level)) {
            if (level.malformed()) {
                throw levelError();
            }
            lv = VideoListQuery.parseInt("level", level.value(), MIN_LEVEL, MAX_LEVEL);
        }
        String c = rawText("classes", classes, MAX_CLASSES_LENGTH, false, searchEnabled);
        return new VideoFilter(k, a, l, y, lv, c);
    }

    /** 不带任何新条件（列表查询与 1.0.0 相同）。 */
    public boolean isEmpty() {
        return keyword == null && area == null && lang == null && year == null && level == null && classes == null;
    }

    /** 带包含匹配条件（受搜索开关与并发上限约束）。 */
    public boolean usesContainsMatch() {
        return keyword != null || classes != null;
    }

    /**
     * keyword 的 LIKE 模式：原值一项；含 {@code & " ' < >} 时另加 mac_filter_xss 形式
     * （application/common.php:1222-1225，htmlspecialchars ENT_QUOTES）一项，两项之间为 OR。未设置时为 {@code null}。
     */
    public List<String> keywordPatterns() {
        if (keyword == null) {
            return null;
        }
        String entities = htmlEntities(keyword);
        return entities.equals(keyword) ? List.of(contains(keyword)) : List.of(contains(keyword), contains(entities));
    }

    /** classes 的 LIKE 模式（不做实体变体）。未设置时为 {@code null}。 */
    public String classesPattern() {
        return classes == null ? null : contains(classes);
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

    /** 步骤 4–7。{@code null} 与空串都表示未设置。 */
    private static String text(String field, String v, int max, boolean commaAllowed) {
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

    private static String year(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.length() != 4 || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.invalidParameter("year", BAD_YEAR);
        }
        return v;
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
