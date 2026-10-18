package com.maccms.backend.content.application;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.infrastructure.SortColumn;
import java.util.List;

/**
 * 一类内容（文章、专题、演员、角色、网址）在给定分类范围与条件下的只读切片：计数与按排序取行用同一组条件（count/list 一致）。
 * 由各 ReadService 构造（分类可见性、发布状态已并入），契约 1.2.0 的列表与 B05 的 SSR 筛选/搜索页（{@link LibrarySearchService}，
 * 含随机页）共用；异常转换由调用方的 {@link ContentReadService#read} 负责。
 */
interface LibrarySlice<T> {

    /** 满足条件的行数；分类范围为空时为 0，不查库。 */
    long count();

    /** 按排序取一段（排序列由 Mapper 的 XML 写死，追加主键同向排序）。只在 {@link #count()} 大于 offset 时调用。 */
    List<T> rows(SortColumn sort, boolean descending, long offset, int limit);

    static <T> LibrarySlice<T> empty() {
        return new LibrarySlice<>() {
            @Override
            public long count() {
                return 0;
            }

            @Override
            public List<T> rows(SortColumn sort, boolean descending, long offset, int limit) {
                return List.of();
            }
        };
    }

    /** 第 page 页（先计数，越界或为 0 时不取行）。 */
    static <T> PageResponse<T> page(LibrarySlice<T> slice, SortColumn sort, boolean descending, int page,
                                    int pageSize) {
        long total = slice.count();
        long offset = (long) (page - 1) * pageSize;
        if (total == 0 || offset >= total) {
            return PageResponse.of(List.of(), page, pageSize, total);
        }
        return PageResponse.of(slice.rows(sort, descending, offset, pageSize), page, pageSize, total);
    }
}
