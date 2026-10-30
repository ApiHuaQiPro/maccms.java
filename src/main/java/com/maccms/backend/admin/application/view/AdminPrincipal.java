package com.maccms.backend.admin.application.view;

/**
 * 当前请求的管理员：每个后台请求按会话里的 {@code admin_id} 重读的 {@code mac_admin} 当前行（D2 ⑤），不含密码。
 * 旧版用登录时存进会话的整行快照（{@code common/model/Admin.php:145-146}），见 21 文档 D-B12-1。
 */
public record AdminPrincipal(long adminId, String adminName, String adminAuth, long loginNum, long lastLoginTime,
                             long lastLoginIp) {

    /** 旧 {@code check_auth} 的超级管理员（{@code admin_id == 1}）。 */
    public boolean superAdmin() {
        return adminId == 1;
    }
}
