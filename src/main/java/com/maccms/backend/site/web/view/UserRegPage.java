package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 注册页（{@code user/reg}，18 文档第 3.4 节）。
 *
 * @param open       开放注册；关闭时只显示说明，没有表单
 * @param captcha    {@code user.reg_verify=1} 时有
 * @param message    {@code reg_phone_sms}/{@code reg_email_sms} 打开时的验证码区（B25 前发送固定失败）
 */
public record UserRegPage(MemberShell shell, boolean open, SafeUrl action, SafeUrl successUrl,
                          Optional<MemberCaptcha> captcha, Optional<UserMessageBlock> message, SafeUrl loginUrl) {
}
