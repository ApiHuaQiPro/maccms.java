package com.maccms.backend.admin.application.view;

/**
 * 管理员表单要显示的一行（旧 Admin::infoData 把 {@code admin_pwd} 清空后整行交给模板，common/model/Admin.php:31-45）。
 * 没有这一行（新增、或 id 不存在）时由调用方显示空表单。
 */
public record AdminFormData(long adminId, String adminName, int adminStatus, String adminAuth) {
}
