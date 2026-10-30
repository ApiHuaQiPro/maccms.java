package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 会员中心列表页的表格（B10，{@code site/member/table.jte}）：表头、行与分页；每行可带一个删除表单（ajax，{@code data-member-form}）。
 *
 * @param caption    表格标题（屏幕阅读器）
 * @param headers    表头
 * @param rows       行；为空时显示 {@code empty}
 * @param empty      没有记录时的说明
 * @param pagination 分页（只有一页时为空）
 */
public record UserTable(String caption, List<String> headers, List<Row> rows, String empty,
                        Optional<PaginationView> pagination) {

    public UserTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }

    /** 有任一行带操作（表格多一列"操作"）。 */
    public boolean hasActions() {
        return rows.stream().anyMatch(r -> r.action().isPresent());
    }

    /** 一格：文字，有链接时输出为链接。 */
    public record Cell(String text, Optional<SafeUrl> link) {

        public static Cell of(String text) {
            return new Cell(text, Optional.empty());
        }

        public static Cell link(String text, SafeUrl url) {
            return new Cell(text, Optional.of(url));
        }
    }

    /** 一行；{@code action} 是该行的删除表单或"去支付"等链接。 */
    public record Row(List<Cell> cells, Optional<MemberAction> action) {

        public Row {
            cells = List.copyOf(cells);
        }
    }
}
