package com.maccms.backend.admin.application.view;

/** 管理员列表的一行（旧 admin/view/admin/index.html 用到的列，不含密码与权限）。 */
public record AdminListRow(long adminId, String adminName, int adminStatus, long lastLoginTime, long lastLoginIp,
                           long loginNum) {
}
