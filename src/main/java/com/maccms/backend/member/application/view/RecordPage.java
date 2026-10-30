package com.maccms.backend.member.application.view;

import java.util.List;

/**
 * 会员中心列表的一页（B10）。
 *
 * @param items 本页的行
 * @param total 符合条件的总行数
 * @param page  页码（≥ 1，旧 {@code intval(page) < 1 ? 1 : …}）
 * @param limit 页大小（按入口的规则取值后，上限 100）
 */
public record RecordPage<T>(List<T> items, long total, int page, int limit) {

    public RecordPage {
        items = List.copyOf(items);
    }

    /** 旧 {@code ceil($total / $limit)}。 */
    public long pageCount() {
        return limit <= 0 ? 0 : (total + limit - 1) / limit;
    }
}
