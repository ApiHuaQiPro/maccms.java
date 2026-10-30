package com.maccms.backend.admin.web.view.member;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;

/** 生成充值卡的表单（旧 admin/view/card/info.html；旧模板里取 {@code $info.link_id} 的隐藏字段永远为空，照旧）。 */
public record CardInfoView(AdminHead head, LegacyLang lang) {
}
