package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 解除绑定页（{@code user/unbind}，B25，32 文档第 7 节）：旧 {@code index/controller/User.php:310-319}。
 *
 * @param ac     {@code phone} 或 {@code email}（参数 {@code ac}，其他值按 {@code email} 显示；提交时服务端照旧校验）
 * @param label  "手机" 或 "邮箱"
 * @param action 解绑提交地址（{@code unbind}）
 */
public record UserUnbindPage(MemberShell shell, String ac, String label, SafeUrl action, SafeUrl successUrl, SafeUrl infoUrl) {
}
