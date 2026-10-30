package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/** 密保问题找回页（{@code user/findpass}，18 文档第 3.9 节）；验证码总是需要。 */
public record UserFindpassPage(MemberShell shell, SafeUrl action, SafeUrl successUrl, MemberCaptcha captcha,
                               SafeUrl emailUrl, SafeUrl phoneUrl, SafeUrl loginUrl) {
}
