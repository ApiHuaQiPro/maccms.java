package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.common.lang.LegacyLang;

/**
 * 采集网址的结果页（旧 admin/view/cj/col_url.html）。旧版在页面之前 {@code mac_echo} 的"让服务器休息一会"或"URL采集完成"与跳转放在 {@code notice}、
 * {@code jumpUrl}（没有下一页时为 null）。
 */
public record CjColUrlView(AdminHead head, LegacyLang lang, CjRunService.ColUrl result, String notice, String jumpUrl, long seconds) {

    /** 旧 {@code {$total-$re}}。 */
    public int distinct() {
        return result.total() - result.re();
    }
}
