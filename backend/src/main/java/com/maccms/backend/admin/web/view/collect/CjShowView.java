package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.common.lang.LegacyLang;

/** 查看一条采集内容（旧 admin/view/cj/show.html）。 */
public record CjShowView(AdminHead head, LegacyLang lang, CjRunService.Shown shown) implements BrowserText {
}
