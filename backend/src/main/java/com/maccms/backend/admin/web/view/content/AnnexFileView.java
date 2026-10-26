package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AnnexDirectory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 附件目录浏览页（旧 admin/view/annex/file.html）。
 *
 * @param today 今天的日期（{@code Y-m-d}，旧 {@code mac_day(…,'color')} 标红）
 */
public record AnnexFileView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AnnexDirectory dir, String today)
        implements BrowserText {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 旧 {@code url('annex/file', ['path' => $path])}。 */
    public String dirUrl(String path) {
        return url.build("annex/file", Map.of("path", path));
    }

    public String day(long t) {
        return t == 0 ? "" : DATE_TIME.format(Instant.ofEpochSecond(t).atZone(PRC));
    }

    public boolean isToday(long t) {
        return t != 0 && day(t).startsWith(today);
    }
}
