package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 登录表单（页面与 {@code ajax_login} 片段共用）。
 *
 * @param action      提交地址（{@code user/login}）
 * @param successUrl  成功后的去处（会员中心）
 * @param captcha     {@code user.login_verify=1} 时有
 * @param regUrl      开放注册时有
 * @param findpassUrl 找回密码
 * @param oauthLinks  已打开的第三方登录（B26：QQ、微信）；未打开或未装配时为空（与旧版配置关闭时一样不显示）
 */
public record UserLoginForm(SafeUrl action, SafeUrl successUrl, Optional<MemberCaptcha> captcha,
                            Optional<SafeUrl> regUrl, SafeUrl findpassUrl, List<OAuthLink> oauthLinks) {

    public UserLoginForm {
        oauthLinks = List.copyOf(oauthLinks);
    }
}
