package com.maccms.backend.content.application.view;

import java.util.List;

/**
 * 后台列表的一页（B13，旧模型 {@code listData} 的返回）。
 *
 * @param page  页码（旧版原样回显 {@code $param['page']}，见各列表的说明）
 * @param limit 每页条数
 * @param wd    处理后的关键字（分页地址里的 {@code wd}）；没有关键字时为 null
 */
public record AdminRowPage(List<AdminRow> rows, long total, long page, long limit, String wd) {

    public AdminRowPage {
        rows = List.copyOf(rows);
    }
}
