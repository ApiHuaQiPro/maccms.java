package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 登录页（旧 admin/view/index/login.html）。
 *
 * @param verify    是否显示验证码（旧 {@code app.admin_login_verify != '0'}）
 * @param verifyUrl 验证码图片：新版是后台自己的 {@code index/verify}（旧版用前台的 {@code /index.php/verify/index.html}，D-B12-4）
 */
public record LoginPage(AdminHead head, LegacyLang lang, boolean verify, String loginUrl, String indexUrl,
                        String verifyUrl) {
}
