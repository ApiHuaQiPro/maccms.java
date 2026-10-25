package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 欢迎页（旧 admin/view/index/welcome.html）。环境信息按新系统取值（21 文档 D-B12-7）：运行环境为操作系统与 Servlet 容器，
 * "PHP版本""ThinkPHP版本"两行新系统没有对应值，显示 {@code -}；数据库升级提示（旧 {@code application/data/update/database.php}）
 * 新系统不存在（零结构变更），不显示。
 *
 * @param loginNum      当前行的登录次数（D2 ⑤ 重读，含本次登录）
 * @param lastLoginIp   {@code long2ip(admin_last_login_ip)}
 * @param lastLoginTime {@code mac_day(admin_last_login_time)}（0 为空串）
 */
public record WelcomePage(AdminHead head, LegacyLang lang, long loginNum, String lastLoginIp, String lastLoginTime,
                          String os, String server, String host, String maxUpload, String date) {
}
