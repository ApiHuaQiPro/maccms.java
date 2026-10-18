package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.LibraryListQuery;
import com.maccms.backend.content.application.LibraryMatch;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoListQuery;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 模块筛选/搜索页（{@link ModuleListPage}）已归一、已校验的条件（B05，15 文档第 7.2 节；独立验收返工第 2 项）。键为旧参数名，值为
 * SSR 逐键归一的不动点：文本键去首尾 W 后不是 {@code ''}、{@code '0'}（与视频搜索页相同）；{@code level}、{@code tid}、{@code rid}、
 * 专题的 {@code id} 为旧 {@code intval} 后的非 0 十进制文本（负数照常筛选）。
 *
 * <p>各值满足 content 的内部查询规则：文章、专题、演员、角色、网址为 {@link LibraryListQuery#of}（旧排序白名单含 rnd/in/referer*）
 * 与 {@link LibraryMatch}（逐键的旧 URL 语义：letter/area/lang/starsign/blood 逗号多值等）；剧情经旧视频标签链读取 URL，与视频搜索页是
 * 同一组键与规则（{@link SearchFilters}）。公开 API 契约不变。按 {@link LegacyUrlBuilder#moduleListLink} 编码后的查询串（含最长页码）
 * 不超过 {@link LegacyUrlBuilder#MAX_SEARCH_LINK_QUERY}。不满足时紧凑构造器抛 {@link ApiException}（INVALID_PARAMETER，不含取值），
 * 所以生成的任何链接经 {@link LegacyUrlParser#parseModuleList} 回读后得到同一组条件。
 *
 * @param module 旧模块名（art、topic、actor、role、website、plot）
 * @param params 条件（按键排序，只读）
 */
public record ModuleFilters(String module, Map<String, String> params) {

    private static final Set<String> INTVAL_KEYS = Set.of("level", "tid", "rid", "id");
    private static final String LONGEST_PAGE = "&page=" + VideoListQuery.MAX_PAGE;

    public ModuleFilters {
        ModuleListPage any = ModuleListPage.byTarget(module + "/search");
        if (any == null || params == null) {
            throw new IllegalArgumentException("未知模块");
        }
        params = Collections.unmodifiableMap(new TreeMap<>(params));
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!any.keys().contains(e.getKey()) || e.getValue() == null) {
                throw new IllegalArgumentException("不支持的条件");
            }
        }
        if (any.kind() == null) {
            // 剧情：视频搜索页的同一套归一与校验（参数错误 → INVALID_PARAMETER）
            if (!LegacyUrlParser.searchFilters(params).params().equals(params)) {
                throw new IllegalArgumentException("条件不是归一后的值");
            }
        } else {
            for (Map.Entry<String, String> e : params.entrySet()) {
                String v = e.getValue();
                boolean fixedPoint = INTVAL_KEYS.contains(e.getKey())
                        ? !v.equals("0") && Long.toString(PhpIntval.of(v)).equals(v) : SearchFilters.isFixedPoint(v);
                if (!fixedPoint) {
                    throw new IllegalArgumentException("条件不是归一后的值");
                }
            }
            // content 的内部查询规则（参数错误 → INVALID_PARAMETER）
            query(any.kind(), params, null, 1, VideoListQuery.DEFAULT_PAGE_SIZE);
        }
        int length = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            length += (length == 0 ? 0 : 1) + e.getKey().length() + 1 + LegacyUrlBuilder.urlencode(e.getValue()).length();
        }
        if (length + LONGEST_PAGE.length() > LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY) {
            throw ApiException.invalidParameter("query", "搜索条件过长");
        }
    }

    public static ModuleFilters none(String module) {
        return new ModuleFilters(module, Map.of());
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    /** 旧 wd（已归一），没有时为 null。 */
    public String keyword() {
        return params.get("wd");
    }

    /** 旧 by=rnd（随机页，不显示分页）。 */
    public boolean random() {
        return LibraryListQuery.RANDOM_SORT.equals(params.get("by"));
    }

    /**
     * 换一个条件的值（{@code null} 为去掉）；新值必须是归一的不动点且满足 content 的规则，否则抛 {@link IllegalArgumentException}
     * （页面据此跳过不能回读的候选值）。
     */
    public ModuleFilters with(String key, String value) {
        Map<String, String> m = new TreeMap<>(params);
        if (value == null) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
        try {
            return new ModuleFilters(module, m);
        } catch (ApiException e) {
            throw new IllegalArgumentException("条件不合法");
        }
    }

    /**
     * 列表查询（旧排序白名单、条件与分页；categoryId 为带分类的筛选页的分类，其余为 null）。
     *
     * @param kind 文章、专题、演员、角色、网址
     */
    public LibraryListQuery query(ContentKind kind, Integer categoryId, int page, int pageSize) {
        return query(kind, params, categoryId, page, pageSize);
    }

    private static LibraryListQuery query(ContentKind kind, Map<String, String> params, Integer categoryId, int page,
                                          int pageSize) {
        return LibraryListQuery.of(kind, categoryId, params.get("by"), params.get("order"), page, pageSize,
                match(params));
    }

    /** 文章、专题、演员、角色、网址的条件（旧参数 → content 的内部条件）。 */
    public LibraryMatch match() {
        return match(params);
    }

    private static LibraryMatch match(Map<String, String> params) {
        return new LibraryMatch(params.get("wd"), params.get("name"), params.get("tag"), params.get("class"),
                params.get("ids"), number(params, "tid"), number(params, "level"), params.get("letter"),
                params.get("sex"), params.get("area"), params.get("lang"), params.get("starsign"), params.get("blood"),
                number(params, "rid"), number(params, "id"));
    }

    /** 剧情搜索页（视频）的条件：与视频搜索页同一套键与规则（含 tid、by=rnd），由页面另加旧 plot=1。 */
    public SearchFilters searchFilters() {
        if (!module.equals("plot")) {
            throw new IllegalStateException("只有剧情页的条件是视频条件");
        }
        return LegacyUrlParser.searchFilters(params);
    }

    private static Long number(Map<String, String> params, String key) {
        String v = params.get(key);
        return v == null ? null : Long.parseLong(v);
    }
}
