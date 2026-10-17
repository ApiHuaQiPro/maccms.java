package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.infrastructure.SortColumn;
import java.util.function.Function;

/**
 * 契约 1.2.0 六类列表（文章、专题、演员、角色、网址、友链）的已校验参数。分页取值与 {@link VideoListQuery} 相同
 * （page 1..1000、pageSize 1..100、默认 20；旧 listCacheData 的 num 默认 20）。紧凑构造器与 {@link #parse} 使用同一套规则：
 * 排序必须在该类型的白名单中，没有分类的类型不能带 categoryId。
 */
public record ContentListQuery(ContentKind kind, Integer categoryId, SortColumn sort, boolean descending, int page,
                               int pageSize, ContentFilter filter) {

    public ContentListQuery {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        if (categoryId != null) {
            if (!kind.hasCategory()) {
                throw new IllegalArgumentException(kind + " 没有分类");
            }
            if (categoryId < 1 || categoryId > VideoListQuery.MAX_CATEGORY_ID) {
                throw range("categoryId", 1, VideoListQuery.MAX_CATEGORY_ID);
            }
        }
        if (sort == null || !kind.sorts().containsValue(sort)) {
            throw ApiException.invalidParameter("sort", "不支持的排序字段");
        }
        if (page < 1 || page > VideoListQuery.MAX_PAGE) {
            throw range("page", 1, VideoListQuery.MAX_PAGE);
        }
        if (pageSize < 1 || pageSize > VideoListQuery.MAX_PAGE_SIZE) {
            throw range("pageSize", 1, VideoListQuery.MAX_PAGE_SIZE);
        }
        if (filter == null) {
            filter = ContentFilter.NONE;
        }
        for (String name : filter.setParameters()) {
            if (!kind.filters().contains(name)) {
                throw new IllegalArgumentException(kind + " 不支持筛选参数 " + name);
            }
        }
    }

    /**
     * 校验顺序：categoryId（只对有分类的类型）→ sort → order → page → pageSize → 精确筛选参数（{@link ContentFilter#parse}）。
     * {@code param} 返回参数的原始解码值（多次出现时以逗号连接，与 1.0.0 既有参数相同），未传为 {@code null}；空字符串按未传处理。
     */
    public static ContentListQuery parse(ContentKind kind, Function<String, String> param) {
        Integer category = null;
        if (kind.hasCategory()) {
            String raw = param.apply("categoryId");
            category = blank(raw) ? null : VideoListQuery.parseInt("categoryId", raw, 1, VideoListQuery.MAX_CATEGORY_ID);
        }
        String rawSort = param.apply("sort");
        SortColumn sort;
        if (blank(rawSort)) {
            sort = kind.defaultSort();
        } else {
            sort = kind.sorts().get(rawSort);
            if (sort == null) {
                throw ApiException.invalidParameter("sort", "不支持的排序字段");
            }
        }
        String order = param.apply("order");
        boolean descending;
        if (blank(order)) {
            descending = kind.defaultDescending();
        } else if ("desc".equals(order)) {
            descending = true;
        } else if ("asc".equals(order)) {
            descending = false;
        } else {
            throw ApiException.invalidParameter("order", "只能是 asc 或 desc");
        }
        String rawPage = param.apply("page");
        int page = blank(rawPage) ? 1 : VideoListQuery.parseInt("page", rawPage, 1, VideoListQuery.MAX_PAGE);
        String rawSize = param.apply("pageSize");
        int size = blank(rawSize) ? VideoListQuery.DEFAULT_PAGE_SIZE
                : VideoListQuery.parseInt("pageSize", rawSize, 1, VideoListQuery.MAX_PAGE_SIZE);
        return new ContentListQuery(kind, category, sort, descending, page, size, ContentFilter.parse(kind, param));
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    private static ApiException range(String field, int min, int max) {
        return ApiException.invalidParameter(field, "必须是 " + min + " 到 " + max + " 之间的整数");
    }
}
