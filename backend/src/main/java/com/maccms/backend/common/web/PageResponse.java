package com.maccms.backend.common.web;

import java.util.List;

/** 分页响应，见 OpenAPI 中各 *Page schema。 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long total, long totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int pageSize, long total) {
        long totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResponse<>(List.copyOf(items), page, pageSize, total, totalPages);
    }
}
