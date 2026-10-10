package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;

/**
 * GET /api/v1/videos 的已校验参数。取值范围是契约的一部分，见 docs/contracts/content-read.openapi.yaml。
 * 空字符串参数按"未传"处理。
 */
public record VideoListQuery(Integer categoryId, VideoSort sort, boolean descending, int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1000;
    public static final int MAX_CATEGORY_ID = 65535;

    public static VideoListQuery parse(String categoryId, String sort, String order, String page, String pageSize) {
        Integer category = blank(categoryId) ? null : parseInt("categoryId", categoryId, 1, MAX_CATEGORY_ID);
        VideoSort videoSort = blank(sort) ? VideoSort.TIME : VideoSort.fromApiValue(sort)
                .orElseThrow(() -> ApiException.invalidParameter("sort", "不支持的排序字段"));
        boolean descending;
        if (blank(order) || "desc".equals(order)) {
            descending = true;
        } else if ("asc".equals(order)) {
            descending = false;
        } else {
            throw ApiException.invalidParameter("order", "只能是 asc 或 desc");
        }
        int p = blank(page) ? 1 : parseInt("page", page, 1, MAX_PAGE);
        int size = blank(pageSize) ? DEFAULT_PAGE_SIZE : parseInt("pageSize", pageSize, 1, MAX_PAGE_SIZE);
        return new VideoListQuery(category, videoSort, descending, p, size);
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    static int parseInt(String field, String raw, int min, int max) {
        if (raw.length() > 10 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.invalidParameter(field, "必须是 " + min + " 到 " + max + " 之间的整数");
        }
        long v = Long.parseLong(raw);
        if (v < min || v > max) {
            throw ApiException.invalidParameter(field, "必须是 " + min + " 到 " + max + " 之间的整数");
        }
        return (int) v;
    }
}
