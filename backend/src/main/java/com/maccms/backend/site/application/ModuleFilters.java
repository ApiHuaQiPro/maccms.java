package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.LibraryMatch;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoListQuery;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 模块筛选/搜索页（{@link ModuleListPage}）已归一、已校验的条件（B05，15 文档第 7.2 节）。键为旧参数名，值为 SSR 逐键归一的不动点：
 * 文本键去首尾 W 后不是 {@code ''}、{@code '0'}（与视频搜索页相同）；{@code level}、{@code tid}、{@code rid} 为旧 {@code intval}
 * 后的非 0 十进制文本。各值满足 content 的同一套规则（{@link ContentListQuery#parse} 的排序白名单与精确筛选、{@link LibraryMatch}、
 * 剧情页为 {@link VideoFilter}），按 {@link LegacyUrlBuilder#moduleListLink} 编码后的查询串（含最长页码）不超过
 * {@link LegacyUrlBuilder#MAX_SEARCH_LINK_QUERY}。不满足时紧凑构造器抛 {@link ApiException}（INVALID_PARAMETER，不含取值），
 * 所以生成的任何链接经 {@link LegacyUrlParser#parseModuleList} 回读后得到同一组条件。
 *
 * @param module 旧模块名（art、topic、actor、role、website、plot）
 * @param params 条件（按键排序，只读）
 */
public record ModuleFilters(String module, Map<String, String> params) {

    private static final Set<String> INTVAL_KEYS = Set.of("level", "tid", "rid");
    private static final String LONGEST_PAGE = "&page=" + VideoListQuery.MAX_PAGE;
    /** 旧参数名 → 契约 1.2.0 列表参数名（{@link ContentListQuery#parse}）。 */
    private static final Map<String, String> API_NAMES = Map.of("sort", "by", "starSign", "starsign", "videoId", "rid");

    public ModuleFilters {
        ModuleListPage any = ModuleListPage.byTarget(module + "/search");
        if (any == null || params == null) {
            throw new IllegalArgumentException("未知模块");
        }
        params = Collections.unmodifiableMap(new TreeMap<>(params));
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (!any.keys().contains(e.getKey()) || v == null) {
                throw new IllegalArgumentException("不支持的条件");
            }
            boolean fixedPoint = INTVAL_KEYS.contains(e.getKey())
                    ? !v.equals("0") && Long.toString(PhpIntval.of(v)).equals(v) : SearchFilters.isFixedPoint(v);
            if (!fixedPoint) {
                throw new IllegalArgumentException("条件不是归一后的值");
            }
        }
        // content 的同一套规则（参数错误 → INVALID_PARAMETER）
        if (any.kind() == null) {
            videoFilter(params);
            videoSorting(params);
        } else {
            query(any.kind(), params, null, 1, VideoListQuery.DEFAULT_PAGE_SIZE);
            match(params);
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
     * 列表查询（排序、精确筛选、分页，契约 1.2.0 的同一套规则；categoryId 为筛选页的分类，搜索页为 null）。
     *
     * @param kind 文章、专题、演员、角色、网址
     */
    public ContentListQuery query(ContentKind kind, Integer categoryId, int page, int pageSize) {
        return query(kind, params, categoryId, page, pageSize);
    }

    private static ContentListQuery query(ContentKind kind, Map<String, String> params, Integer categoryId, int page,
                                          int pageSize) {
        if ("rnd".equals(params.get("by"))) {
            // 旧版各模块的随机排序（by=rnd）新版不提供（15 文档第 11 节），与其他不支持的排序同样 400
            throw ApiException.invalidParameter("sort", "不支持的排序字段");
        }
        return ContentListQuery.parse(kind, p -> switch (p) {
            case "categoryId" -> categoryId == null ? null : Integer.toString(categoryId);
            case "page" -> Integer.toString(page);
            case "pageSize" -> Integer.toString(pageSize);
            default -> params.get(API_NAMES.getOrDefault(p, p));
        });
    }

    /** 包含匹配与 ID 条件（wd、name、tag、class、ids，文章的 tid）。 */
    public LibraryMatch match() {
        return match(params);
    }

    private static LibraryMatch match(Map<String, String> params) {
        String tid = params.get("tid");
        return new LibraryMatch(params.get("wd"), params.get("name"), params.get("tag"), params.get("class"),
                params.get("ids"), tid == null ? null : Long.parseLong(tid));
    }

    /** 剧情搜索页（视频）的条件：wd、level、letter、ids，与视频搜索页同一套规则。 */
    public VideoFilter videoFilter() {
        return videoFilter(params);
    }

    private static VideoFilter videoFilter(Map<String, String> params) {
        String level = params.get("level");
        Integer lv = null;
        if (level != null) {
            long n = Long.parseLong(level);
            if (n < VideoFilter.MIN_LEVEL || n > VideoFilter.MAX_LEVEL) {
                throw ApiException.invalidParameter("level",
                        "必须是 " + VideoFilter.MIN_LEVEL + " 到 " + VideoFilter.MAX_LEVEL + " 之间的整数");
            }
            lv = (int) n;
        }
        return new VideoFilter(params.get("wd"), null, null, null, lv, null, params.get("letter"), null, null, null,
                null, null, null, params.get("ids"));
    }

    /** 剧情搜索页（视频）的排序与方向：与视频搜索页相同（by 为视频的排序名，不含 rnd）。 */
    public VideoListQuery videoSorting() {
        return videoSorting(params);
    }

    private static VideoListQuery videoSorting(Map<String, String> params) {
        String by = params.get("by");
        if ("rnd".equals(by)) {
            throw ApiException.invalidParameter("by", "不支持的排序字段");
        }
        return VideoListQuery.parse(null, by, params.get("order"), null, null);
    }
}
