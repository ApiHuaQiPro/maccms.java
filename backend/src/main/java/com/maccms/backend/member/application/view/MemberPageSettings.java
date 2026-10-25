package com.maccms.backend.member.application.view;

/**
 * 会员页面需要的开关（旧配置 {@code user} 段，18 文档第 7 节）。
 *
 * @param available      会员账号功能可用（可写数据源已打开）；false 时 {@code user/*} 返回 503
 * @param loginVerify    登录需要验证码
 * @param regVerify      注册需要验证码
 * @param regOpen        开放注册（{@code status}、{@code reg_open} 都不为 0）
 * @param regPhoneSms    注册需要手机验证码（B25 前不可用）
 * @param regEmailSms    注册需要邮箱验证码（B25 前不可用）
 * @param portrait       头像上传可用（开关打开、尺寸合法、目录已配置）
 */
public record MemberPageSettings(boolean available, boolean loginVerify, boolean regVerify, boolean regOpen,
                                 boolean regPhoneSms, boolean regEmailSms, boolean portrait) {
}
