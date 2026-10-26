package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminTypeNode;
import java.util.List;

/** 分类列表（旧 admin/view/type/index.html）。 */
public record TypeListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, List<AdminTypeNode> list, FrontLinks links) {

    /** 旧 {@code mac_get_mid_text($mid)}（application/common.php:1072-1087）；不在表中的取值为空串。 */
    public String midText(long mid) {
        return midText(lang, mid);
    }

    /** 同上（评论列表、评论表单也用）。 */
    public static String midText(LegacyLang lang, long mid) {
        String key = switch ((int) mid) {
            case 1 -> "vod";
            case 2 -> "art";
            case 3 -> "topic";
            case 4 -> "comment";
            case 5 -> "gbook";
            case 6 -> "user";
            case 7 -> "label";
            case 8 -> "actor";
            case 9 -> "role";
            case 10 -> "plot";
            case 11 -> "website";
            default -> null;
        };
        return key == null || mid < 1 || mid > 11 ? "" : lang.get(key);
    }
}
