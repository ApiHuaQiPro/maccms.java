package com.maccms.backend.admin.infrastructure;

/**
 * {@code mac_admin} 的一行（install.sql:72-86）。{@code admin_random} 只有旧版未启用的 cookie 登录（{@code checkLogin2}）读，
 * 新版不读；整行不返回给页面，页面只拿 application 层的视图。
 */
public record AdminRow(long adminId, String adminName, String adminPwd, int adminStatus, String adminAuth,
                       long adminLoginTime, long adminLoginIp, long adminLoginNum, long adminLastLoginTime,
                       long adminLastLoginIp) {
}
