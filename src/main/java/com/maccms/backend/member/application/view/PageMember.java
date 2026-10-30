package com.maccms.backend.member.application.view;

import java.util.List;

/**
 * 一次前台页面请求的 {@code label_user} 结果（B09，18 文档第 5 节）：会员登录态，以及本次要写出的 {@code Set-Cookie}
 * （校验失败的游客回写，或到期降级后的组 cookie 重发）。
 *
 * @param state          登录态（游客时 {@code member=false}）
 * @param setCookies     要写出的 {@code Set-Cookie} 头值
 * @param memberCookies  请求带了三个会员 cookie 且通过了前置判断（查过库）；用于把页面缓存改为 private
 */
public record PageMember(MemberState state, List<String> setCookies, boolean memberCookies) {

    public PageMember {
        setCookies = List.copyOf(setCookies);
    }
}
