package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import java.util.Map;
import java.util.TreeMap;

/**
 * SSR 搜索页（旧 {@code vod/search}，固定路径 + 查询串）已归一、已校验的条件（09 方案第 7.2.3 节；B05 起承接 B04 的新键、
 * {@code tid} 与 {@code by=rnd}，docs/architecture/15-b05-readonly-pages.md 第 3 节）。字段为 {@code null} 表示未设置；
 * {@code keyword}/{@code classes}/{@code sort} 对应旧参数 {@code wd}/{@code class}/{@code by}，其余同名。
 *
 * <p>不变式：文本值满足 content 的同一套规则（{@link VideoFilter} 的紧凑构造器），并且是 SSR 归一的不动点——不是
 * {@code ''}、{@code '0'}，首尾没有空白字符 W（09 方案第 4.2 节）；{@code year} 不是 intval 为 0 的写法（{@code 0000}、
 * {@code 0000-0000}，旧版不筛选）；{@code tid} 不为 0（旧版 intval 为 0 时不筛选）；{@code random} 与 {@code sort} 不同时设置；
 * 按 {@link LegacyUrlBuilder#vodSearchLink} 的写法编码后，查询串（含最长的 {@code page=1000}）不超过
 * {@link LegacyUrlBuilder#MAX_SEARCH_LINK_QUERY}，所以生成的任何页内链接都在 {@link SafeUrl} 上限内。因此生成的链接经
 * {@link LegacyUrlParser#parseSearch} 回读后得到同一组条件（第 7.2.5 节）。不满足时抛 {@link IllegalArgumentException}，信息不含取值。
 *
 * @param level  1..9
 * @param tid    旧 tid（intval 后不为 0；内部条件 {@code type_id = tid OR type_id_1 = tid}，公开 API 不新增，队长裁定 Q2）
 * @param sort   旧 {@code by}（不含 rnd）
 * @param random 旧 {@code by=rnd}（随机取页，契约 1.3.0 的同一算法）
 * @param order  旧 {@code order}
 */
public record SearchFilters(String keyword, String area, String lang, String year, Integer level, String classes,
                            String letter, String state, String version, String actor, String director, String tag,
                            String name, String ids, Long tid, VideoSort sort, boolean random, SortOrder order) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null, null, null, null);

    /** 链接中的最长页码参数，计入查询串预算。 */
    private static final String LONGEST_PAGE = "&page=" + VideoListQuery.MAX_PAGE;

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
        for (String v : new String[] {keyword, area, lang, year, classes, letter, state, version, actor, director, tag,
            name, ids}) {
            if (v != null && !isFixedPoint(v)) {
                throw new IllegalArgumentException("搜索条件不是归一后的值");
            }
        }
        if (year != null && yearIsZero(year)) {
            throw new IllegalArgumentException("搜索条件不是归一后的值");
        }
        if (tid != null && tid == 0) {
            throw new IllegalArgumentException("搜索条件不是归一后的值");
        }
        if (random && sort != null) {
            throw new IllegalArgumentException("随机排序与排序字段只能设置一个");
        }
        try {
            new VideoFilter(keyword, area, lang, year, level, classes, letter, state, version, actor, director, tag,
                    name, ids);
        } catch (ApiException e) {
            throw new IllegalArgumentException("搜索条件不合法");
        }
        if (encodedLength(params(keyword, area, lang, year, level, classes, letter, state, version, actor, director,
                tag, name, ids, tid, sort, random, order)) + LONGEST_PAGE.length()
                > LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY) {
            throw new IllegalArgumentException("搜索条件过长");
        }
    }

    /** MACC-20 的 8 个条件（其余未设置）。 */
    public SearchFilters(String keyword, String area, String lang, String year, Integer level, String classes,
                         VideoSort sort, SortOrder order) {
        this(keyword, area, lang, year, level, classes, null, null, null, null, null, null, null, null, null, sort,
                false, order);
    }

    /** 没有任何条件（排序也未设置）。 */
    public boolean isEmpty() {
        return params().isEmpty();
    }

    /** 带包含匹配条件（受 {@code maccms.content.search.enabled} 控制）：wd、class、actor、director、tag、name。 */
    public boolean usesContainsMatch() {
        return firstContainsMatchParameter() != null;
    }

    /** 第一个包含匹配条件的 content 参数名（keyword、classes、actor、director、tag、name），没有时为 {@code null}。 */
    public String firstContainsMatchParameter() {
        return keyword != null ? "keyword" : classes != null ? "classes" : actor != null ? "actor"
                : director != null ? "director" : tag != null ? "tag" : name != null ? "name" : null;
    }

    /** content 的筛选条件（排序另由调用方传入 VideoListQuery）。 */
    public VideoFilter toVideoFilter() {
        return new VideoFilter(keyword, area, lang, year, level, classes, letter, state, version, actor, director, tag,
                name, ids);
    }

    /** content 的内部条件：只有 tid（旧 {@code type_id = tid OR type_id_1 = tid}）。 */
    public VideoLegacyConditions toLegacyConditions() {
        return tid == null ? VideoLegacyConditions.NONE : VideoLegacyConditions.builder().tid(tid).build();
    }

    /**
     * 已设置的条件，键为旧参数名，按键排序（与 {@code mac_url} 一致，application/common.php:1732-1746），值是解码后的文本；
     * 不含页码。随机排序为 {@code by=rnd}。
     */
    public Map<String, String> params() {
        return params(keyword, area, lang, year, level, classes, letter, state, version, actor, director, tag, name,
                ids, tid, sort, random, order);
    }

    private static Map<String, String> params(String keyword, String area, String lang, String year, Integer level,
                                              String classes, String letter, String state, String version,
                                              String actor, String director, String tag, String name, String ids,
                                              Long tid, VideoSort sort, boolean random, SortOrder order) {
        Map<String, String> m = new TreeMap<>();
        put(m, "actor", actor);
        put(m, "area", area);
        put(m, "by", random ? "rnd" : sort == null ? null : sort.apiValue());
        put(m, "class", classes);
        put(m, "director", director);
        put(m, "ids", ids);
        put(m, "lang", lang);
        put(m, "letter", letter);
        put(m, "level", level == null ? null : Integer.toString(level));
        put(m, "name", name);
        put(m, "order", order == null ? null : order.value());
        put(m, "state", state);
        put(m, "tag", tag);
        put(m, "tid", tid == null ? null : Long.toString(tid));
        put(m, "version", version);
        put(m, "wd", keyword);
        put(m, "year", year);
        return m;
    }

    /** {@code k=urlencode(v)&…} 的长度（不含页码）。 */
    private static int encodedLength(Map<String, String> params) {
        int n = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            n += (n == 0 ? 0 : 1) + e.getKey().length() + 1 + LegacyUrlBuilder.urlencode(e.getValue()).length();
        }
        return n;
    }

    /**
     * 把一个文本条件（旧参数名 {@code wd}、{@code area}、{@code lang}、{@code year}、{@code class}、{@code letter}、
     * {@code state}、{@code version}、{@code actor}、{@code director}、{@code tag}、{@code name}、{@code ids}）换成给定值，
     * {@code null} 表示去掉该条件；{@code tid} 只能去掉（{@code null}）。值不满足不变式时抛 {@link IllegalArgumentException}。
     */
    public SearchFilters with(String key, String value) {
        return switch (key) {
            case "wd" -> copy(value, area, lang, year, classes, letter, state, version, actor, director, tag, name, ids,
                    tid);
            case "area" -> copy(keyword, value, lang, year, classes, letter, state, version, actor, director, tag, name,
                    ids, tid);
            case "lang" -> copy(keyword, area, value, year, classes, letter, state, version, actor, director, tag, name,
                    ids, tid);
            case "year" -> copy(keyword, area, lang, value, classes, letter, state, version, actor, director, tag, name,
                    ids, tid);
            case "class" -> copy(keyword, area, lang, year, value, letter, state, version, actor, director, tag, name,
                    ids, tid);
            case "letter" -> copy(keyword, area, lang, year, classes, value, state, version, actor, director, tag, name,
                    ids, tid);
            case "state" -> copy(keyword, area, lang, year, classes, letter, value, version, actor, director, tag, name,
                    ids, tid);
            case "version" -> copy(keyword, area, lang, year, classes, letter, state, value, actor, director, tag, name,
                    ids, tid);
            case "actor" -> copy(keyword, area, lang, year, classes, letter, state, version, value, director, tag, name,
                    ids, tid);
            case "director" -> copy(keyword, area, lang, year, classes, letter, state, version, actor, value, tag, name,
                    ids, tid);
            case "tag" -> copy(keyword, area, lang, year, classes, letter, state, version, actor, director, value, name,
                    ids, tid);
            case "name" -> copy(keyword, area, lang, year, classes, letter, state, version, actor, director, tag, value,
                    ids, tid);
            case "ids" -> copy(keyword, area, lang, year, classes, letter, state, version, actor, director, tag, name,
                    value, tid);
            case "tid" -> {
                if (value != null) {
                    throw new IllegalArgumentException("tid 只能去掉");
                }
                yield copy(keyword, area, lang, year, classes, letter, state, version, actor, director, tag, name, ids,
                        null);
            }
            default -> throw new IllegalArgumentException("不支持的参数");
        };
    }

    private SearchFilters copy(String k, String a, String l, String y, String c, String lt, String st, String v,
                               String ac, String d, String tg, String n, String i, Long t) {
        return new SearchFilters(k, a, l, y, level, c, lt, st, v, ac, d, tg, n, i, t, sort, random, order);
    }

    /** 换成给定排序字段（{@code null} 表示去掉），方向不变；随机排序随之去掉。 */
    public SearchFilters withSort(VideoSort newSort) {
        return new SearchFilters(keyword, area, lang, year, level, classes, letter, state, version, actor, director,
                tag, name, ids, tid, newSort, false, order);
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

    /**
     * 旧版不筛选的年份写法：4 个字符且 intval 为 0（{@code 0000}），或 9 个字符且两端 intval 都为 0（{@code 0000-0000}）——
     * 旧 Vod.php:183-201 先 intval，结果为 0 时 {@code !empty()} 为假（B05 逐键零值归一）。
     */
    public static boolean yearIsZero(String y) {
        if (y.length() == 4) {
            return PhpIntval.of(y) == 0;
        }
        if (y.length() == 9) {
            return PhpIntval.of(y.substring(0, 4)) == 0
                    && PhpIntval.of(y.substring(5, 9)) == 0;
        }
        return false;
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
