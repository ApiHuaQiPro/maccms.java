package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 旧 {@code public/verify}（B11，{@code pages/verify.jte}）：旧配置 {@code search_verify}/{@code show_verify} 打开且本会话还没有通过时，搜索页、
 * 筛选页显示的验证码页。表单提交到旧接口 {@code ajax/verify_check}（{@code type}、{@code verify}），另带本页地址 {@code back}，通过后的提示页
 * 链接回来（页面不带脚本）。
 *
 * @param type {@code search} 或 {@code show}
 * @param back 本页的站内地址（相对站点根的路径与查询串，由服务端生成）
 */
public record VerifyPage(PageChrome chrome, String heading, SafeUrl action, String type, String back,
                         MemberCaptcha captcha) {
}
