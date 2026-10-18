package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 邮箱/手机找回页（{@code user/findpass_msg}，18 文档第 3.6 节）：B25 前发送验证码固定返回"未配置"，重置固定返回验证信息错误。
 *
 * @param message     发送验证码区（{@code ac} 与提交地址）
 * @param resetAction 重置提交地址（{@code findpass_reset}）
 */
public record UserFindpassMsgPage(MemberShell shell, UserMessageBlock message, SafeUrl resetAction, SafeUrl loginUrl) {
}
