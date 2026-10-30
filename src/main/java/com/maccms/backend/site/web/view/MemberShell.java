package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 会员页面的共同部分（B09，18 文档第 8 节）：外壳、本响应的脚本 nonce（会员页 CSP 为 {@code script-src 'nonce-…'}）与
 * {@code member.js} 的地址（前端维护的同源脚本）。
 */
public record MemberShell(PageChrome chrome, String nonce, SafeUrl script) {
}
