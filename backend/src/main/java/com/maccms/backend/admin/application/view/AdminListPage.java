package com.maccms.backend.admin.application.view;

import java.util.List;

/**
 * 管理员列表的一页（旧 admin/controller/Admin.php:12-40）。
 *
 * @param wd    筛选词（旧版 {@code htmlspecialchars(urldecode(wd))} 之后的值，没有筛选时为 null），分页地址要带上它
 * @param page  当前页（{@code intval}，小于 1 时为 1）
 * @param limit 每页条数（{@code intval}，小于 1 时为 {@code app.pagesize}，最多 {@value com.maccms.backend.admin.application.AdminAccountService#MAX_LIMIT}）
 */
public record AdminListPage(List<AdminListRow> rows, long total, long page, int limit, String wd) {
}
