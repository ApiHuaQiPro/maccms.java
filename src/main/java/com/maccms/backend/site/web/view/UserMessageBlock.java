package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 手机/邮箱验证码区（注册页与邮箱/手机找回页）。
 *
 * @param ac         {@code phone} 或 {@code email}
 * @param label      "手机" 或 "邮箱"
 * @param sendAction 发送验证码的提交地址（{@code reg_msg} 或 {@code findpass_msg}）
 */
public record UserMessageBlock(String ac, String label, SafeUrl sendAction) {
}
