package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.SortColumn;

/**
 * 文章、专题、演员、角色、网址 SSR 筛选/搜索页的内部列表查询（B05，15 文档第 7.2 节；独立验收返工第 2 项）。不是公开 API：
 * 契约 1.2.0 的 {@link ContentListQuery} 不变，这里另按旧 {@code listCacheData} 的排序白名单（含 URL 可达的 {@code rnd}、
 * {@code in}、网址的 {@code referer*}）与 {@link LibraryMatch} 的条件查询。可见性（分类可见、发布状态）、每页条数与页码上限与契约
 * 1.2.0 的列表相同。
 *
 * @param categoryId 筛选页的分类（本身 + 直接子分类，只对有分类的类型）；搜索页为 null
 * @param sort       排序列；随机排序时固定为 {@link SortColumn#HITS_WEEK}
 * @param random     旧 {@code by=rnd}（见 {@link LibrarySearchService}：随机页，忽略请求的页码）
 */
public record LibraryListQuery(ContentKind kind, Integer categoryId, SortColumn sort, boolean descending,
                               boolean random, int page, int pageSize, LibraryMatch match) {

    public static final String RANDOM_SORT = "rnd";
    /** 旧 {@code by=in}：演员的 name 只来自模板（URL 读不到），等同 time；网址有 name 时按名单顺序，否则等同 time。 */
    public static final String IN_SORT = "in";

    public LibraryListQuery {
        if (kind == null || kind == ContentKind.LINK) {
            throw new IllegalArgumentException("kind");
        }
        if (match == null) {
            match = LibraryMatch.NONE;
        }
        match.check(kind);
        if (categoryId != null) {
            if (!kind.hasCategory()) {
                throw new IllegalArgumentException(kind + " 没有分类");
            }
            if (categoryId < 1 || categoryId > VideoListQuery.MAX_CATEGORY_ID) {
                throw new IllegalArgumentException("categoryId 越界");
            }
        }
        if (random && (sort != SortColumn.HITS_WEEK || !descending)) {
            throw new IllegalArgumentException("随机排序固定为 hits_week 倒序");
        }
        boolean nameOrder = sort == SortColumn.NAME_ORDER;
        if (sort == null || (!kind.sorts().containsValue(sort) && !nameOrder)) {
            throw new IllegalArgumentException("不支持的排序");
        }
        if (nameOrder && (kind != ContentKind.WEBSITE || match.name() == null || descending)) {
            throw new IllegalArgumentException("按名单排序只用于有 name 的网址，方向为升序");
        }
        if (page < 1 || page > VideoListQuery.MAX_PAGE || pageSize < 1 || pageSize > VideoListQuery.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("分页越界");
        }
    }

    /**
     * 按旧 by/order（SSR 已归一，{@code null} 为未传）组装。by：未传为 time；{@code rnd} 为随机；{@code in} 见 {@link #IN_SORT}；
     * 其余须在该类型的旧白名单中（{@link ContentKind#sorts()}）。order：未传为 desc，只认 asc/desc。随机与按名单排序时 order
     * 照常校验但不影响结果（旧版分别固定为 desc 与 find_in_set 升序）。旧版对不在白名单中的 by/order 回落为 time/desc，新版
     * 400（与视频搜索页相同，15 文档第 11 节）。
     */
    public static LibraryListQuery of(ContentKind kind, Integer categoryId, String by, String order, int page,
                                      int pageSize, LibraryMatch match) {
        boolean descending;
        if (order == null) {
            descending = kind.defaultDescending();
        } else if ("desc".equals(order)) {
            descending = true;
        } else if ("asc".equals(order)) {
            descending = false;
        } else {
            throw ApiException.invalidParameter("order", "只能是 asc 或 desc");
        }
        LibraryMatch m = match == null ? LibraryMatch.NONE : match;
        if (by == null) {
            return new LibraryListQuery(kind, categoryId, kind.defaultSort(), descending, false, page, pageSize, m);
        }
        if (RANDOM_SORT.equals(by)) {
            return new LibraryListQuery(kind, categoryId, SortColumn.HITS_WEEK, true, true, page, pageSize, m);
        }
        if (IN_SORT.equals(by) && (kind == ContentKind.ACTOR || kind == ContentKind.WEBSITE)) {
            if (kind == ContentKind.WEBSITE && m.name() != null) {
                return new LibraryListQuery(kind, categoryId, SortColumn.NAME_ORDER, false, false, page, pageSize, m);
            }
            return new LibraryListQuery(kind, categoryId, SortColumn.TIME, descending, false, page, pageSize, m);
        }
        SortColumn sort = kind.sorts().get(by);
        if (sort == null) {
            throw ApiException.invalidParameter("by", "不支持的排序字段");
        }
        return new LibraryListQuery(kind, categoryId, sort, descending, false, page, pageSize, m);
    }

    /** 该类型可用的旧 by 取值（含 rnd、演员与网址的 in）。 */
    public static boolean acceptsSort(ContentKind kind, String by) {
        return RANDOM_SORT.equals(by) || kind.sorts().containsKey(by)
                || (IN_SORT.equals(by) && (kind == ContentKind.ACTOR || kind == ContentKind.WEBSITE));
    }

    ContentCriteria criteria() {
        return match.toCriteria(kind, sort == SortColumn.NAME_ORDER);
    }
}
