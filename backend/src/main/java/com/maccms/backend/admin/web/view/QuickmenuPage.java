package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 快捷菜单配置页（旧 admin/view/index/quickmenu.html）。
 *
 * @param token     表单令牌（旧 {@code {$Request.token}}）
 * @param quickmenu 旧 {@code join(chr(13), array_values(config('quickmenu')))}
 */
public record QuickmenuPage(AdminHead head, LegacyLang lang, String token, String quickmenu) {
}
