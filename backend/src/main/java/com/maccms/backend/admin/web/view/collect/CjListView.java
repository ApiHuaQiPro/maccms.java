package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 自定义规则节点列表（旧 admin/view/cj/index.html）。 */
public record CjListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list) implements BrowserText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /** 旧 {@code {$vo.lastdate|mac_day}}：0 为空串，否则 {@code Y-m-d H:i:s}（PRC）。 */
    public String lastdate(AdminRow vo) {
        long t = vo.n("lastdate");
        return t == 0 ? "" : TIME.format(Instant.ofEpochSecond(t));
    }
}
