package com.maccms.backend.site.web.view;

/** 登录页（{@code user/login}，18 文档第 3.2 节）。表单部分与 {@code ajax_login} 片段共用 {@link UserLoginForm}。 */
public record UserLoginPage(MemberShell shell, UserLoginForm form) {
}
