package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;

/**
 * 后台列表的页码与每页条数（B13，与 B12 管理员列表相同，21 文档 D-B12-11）：旧版 {@code intval($param['page']) < 1 ? 1 : $param['page']}、
 * {@code intval($param['limit']) < 1 ? 后台每页条数 : $param['limit']}，原值写进分页脚本（{@code 2abc} 时脚本出错）。新版一律按 {@code intval}，
 * 每页最多 1000 条，页码超过一百万时返回空页。
 */
public record AdminPaging(long page, int limit) {

    public static final int MAX_LIMIT = 1000;
    static final long MAX_PAGE = 1_000_000;

    public static AdminPaging of(byte[] page, byte[] limit, int pageSize) {
        long p = page == null ? 0 : PhpIntval.of(new String(page, StandardCharsets.ISO_8859_1));
        long l = limit == null ? 0 : PhpIntval.of(new String(limit, StandardCharsets.ISO_8859_1));
        return new AdminPaging(p < 1 ? 1 : p, (int) (l < 1 ? pageSize : Math.min(l, MAX_LIMIT)));
    }

    public long offset() {
        return (page - 1) * limit;
    }

    /** 页码过大（不查询，返回空页）。 */
    public boolean beyond() {
        return page > MAX_PAGE;
    }
}
