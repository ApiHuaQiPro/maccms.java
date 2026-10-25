package com.maccms.backend.admin.web.view;

/**
 * 不跳转的提示页（结构取自旧 admin/view/public/msg.html）：后续批次才实现的后台动作、页面不存在。
 */
public record NoticePage(String title, String msg) {
}
