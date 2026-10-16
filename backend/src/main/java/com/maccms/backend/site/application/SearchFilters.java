package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoSort;
import java.util.Map;
import java.util.TreeMap;

/**
 * SSR 搜索页（旧 {@code vod/search}，固定路径 + 查询串）已归一、已校验的条件（09 方案第 7.2.3 节）。字段为 {@code null}
 * 表示未设置；{@code keyword}/{@code classes}/{@code sort} 对应旧参数 {@code wd}/{@code class}/{@code by}。
 *
 * <p>不变式：文本值满足 content 的同一套规则（{@link VideoFilter} 的紧凑构造器），并且是 SSR 归一的不动点——不是
 * {@code ''}、{@code '0'}，首尾没有空白字符 W（09 方案第 4.2 节）。因此 {@link LegacyUrlBuilder#vodSearchLink} 生成的链接
 * 经 {@link LegacyUrlParser#parseSearch} 回读后得到同一组条件（第 7.2.5 节）。不满足时抛 {@link IllegalArgumentException}，
 * 信息不含取值。
 *
 * @param level 1..9
 * @param sort  旧 {@code by}（不含 rnd）
 * @param order 旧 {@code order}
 */
public record SearchFilters(String keyword, String area, String lang, String year, Integer level, String classes,
                            VideoSort sort, SortOrder order) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null, null, null, null);

    /** 旧 {@code order}：asc / desc。 */
    public enum SortOrder {
        ASC("asc"), DESC("desc");

        private final String value;

        SortOrder(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public SearchFilters {
        for (String v : new String[] {keyword, area, lang, year, classes}) {
            if (v != null && !isFixedPoint(v)) {
                throw new IllegalArgumentException("搜索条件不是归一后的值");
            }
        }
        try {
            new VideoFilter(keyword, area, lang, year, level, classes);
        } catch (ApiException e) {
            throw new IllegalArgumentException("搜索条件不合法");
        }
    }

    /** 没有任何条件（排序也未设置）。 */
    public boolean isEmpty() {
        return keyword == null && area == null && lang == null && year == null && level == null && classes == null
                && sort == null && order == null;
    }

    /** 带包含匹配条件（受 {@code maccms.content.search.enabled} 控制）。 */
    public boolean usesContainsMatch() {
        return keyword != null || classes != null;
    }

    /** content 的筛选条件（排序另由调用方传入 VideoListQuery）。 */
    public VideoFilter toVideoFilter() {
        return new VideoFilter(keyword, area, lang, year, level, classes);
    }

    /**
     * 已设置的条件，键为旧参数名，按键排序（与 {@code mac_url} 一致，application/common.php:1732-1746），值是解码后的文本；
     * 不含页码。
     */
    public Map<String, String> params() {
        Map<String, String> m = new TreeMap<>();
        put(m, "area", area);
        put(m, "by", sort == null ? null : sort.apiValue());
        put(m, "class", classes);
        put(m, "lang", lang);
        put(m, "level", level == null ? null : Integer.toString(level));
        put(m, "order", order == null ? null : order.value());
        put(m, "wd", keyword);
        put(m, "year", year);
        return m;
    }

    /**
     * 把一个文本条件（旧参数名 {@code wd}、{@code area}、{@code lang}、{@code year}、{@code class}）换成给定值，
     * {@code null} 表示去掉该条件。值不满足不变式时抛 {@link IllegalArgumentException}。
     */
    public SearchFilters with(String key, String value) {
        return switch (key) {
            case "wd" -> new SearchFilters(value, area, lang, year, level, classes, sort, order);
            case "area" -> new SearchFilters(keyword, value, lang, year, level, classes, sort, order);
            case "lang" -> new SearchFilters(keyword, area, value, year, level, classes, sort, order);
            case "year" -> new SearchFilters(keyword, area, lang, value, level, classes, sort, order);
            case "class" -> new SearchFilters(keyword, area, lang, year, level, value, sort, order);
            default -> throw new IllegalArgumentException("不支持的参数");
        };
    }

    /** 换成给定排序字段（{@code null} 表示去掉），方向不变。 */
    public SearchFilters withSort(VideoSort newSort) {
        return new SearchFilters(keyword, area, lang, year, level, classes, newSort, order);
    }

    /**
     * SSR 的旧站式归一（对应旧 {@code trim}，application/common.php:1582-1594）：去掉首尾的 W 字符。
     * W 与 content 校验用的空白集合相同（ECMAScript {@code \s}，09 方案第 4.2 节），不用 trim/strip/isWhitespace。
     */
    public static String stripW(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && whitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && whitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 归一的不动点：非空、不是 {@code '0'}、首尾没有 W 字符（旧 {@code !empty()} 与 trim，Vod.php:174-240）。 */
    public static boolean isFixedPoint(String v) {
        return !v.isEmpty() && !v.equals("0") && !whitespace(v.charAt(0)) && !whitespace(v.charAt(v.length() - 1));
    }

    /** 与 content 的 VideoFilter 相同的空白集合（ECMAScript {@code \s}）。 */
    static boolean whitespace(char c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
