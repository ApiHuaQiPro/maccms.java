package com.maccms.backend.site.web.view;

import java.util.List;
import java.util.Optional;

/**
 * 评论或留言的一页（B11）。
 *
 * @param total      已审核的条数
 * @param empty      没有记录时的说明
 * @param pagination 分页（只有一页时为空）
 */
public record BoardList<T>(List<T> entries, long total, String empty, Optional<PaginationView> pagination) {

    public BoardList {
        entries = List.copyOf(entries);
    }
}
