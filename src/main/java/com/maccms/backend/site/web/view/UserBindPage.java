package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 绑定手机/邮箱页（{@code user/bind}，B25，32 文档第 7 节）：旧 {@code index/controller/User.php:294-308}，{@code ac} 缺省为 {@code email}。
 *
 * @param message    发送验证码区（{@code ac} 与 {@code bindmsg} 地址）
 * @param action     绑定提交地址（{@code bind}）
 * @param switchUrl  切换到另一种方式的地址
 * @param switchText 切换链接的文字
 */
public record UserBindPage(MemberShell shell, UserMessageBlock message, SafeUrl action, SafeUrl successUrl,
                           SafeUrl switchUrl, String switchText, SafeUrl infoUrl) {
}
