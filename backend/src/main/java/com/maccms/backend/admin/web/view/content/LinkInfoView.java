package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;

/** 友情链接表单（旧 admin/view/link/info.html）；新增时 {@code info} 为空行。 */
public record LinkInfoView(AdminHead head, LegacyLang lang, AdminRow info) {
}
