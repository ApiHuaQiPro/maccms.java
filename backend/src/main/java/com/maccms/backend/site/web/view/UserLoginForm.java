package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 登录表单（页面与 {@code ajax_login} 片段共用）。
 *
 * @param action      提交地址（{@code user/login}）
 * @param successUrl  成功后的去处（会员中心）
 * @param captcha     {@code user.login_verify=1} 时有
 * @param regUrl      开放注册时有
 * @param findpassUrl 找回密码
 */
public record UserLoginForm(SafeUrl action, SafeUrl successUrl, Optional<MemberCaptcha> captcha,
                            Optional<SafeUrl> regUrl, SafeUrl findpassUrl) {
}
