package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 上传弹窗页（旧 admin/view/upload/index.html，Upload::index）：旧模板只有一个表单片段（不带后台外壳），{@code path}、{@code id} 原样写进
 * 隐藏字段（旧版不转义，新版转义）。旧模板不闭合 body 标签（浏览器自动闭合）；jte 要求标签闭合，页面末尾补上闭合标签，其余照旧。
 *
 * @param action 表单提交地址（旧 {@code url('upload/upload')}）
 */
public record UploadIndexView(LegacyLang lang, String action, String path, String id) {
}
