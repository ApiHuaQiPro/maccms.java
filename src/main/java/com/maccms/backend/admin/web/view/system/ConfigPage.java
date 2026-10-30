package com.maccms.backend.admin.web.view.system;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.Map;

/**
 * B15 的表单与列表页（旧 admin/view/system、vodplayer、vodserver、voddowner、domain、timming、urlsend、database、addon、template 下的模板）。
 *
 * @param token 本次签发的表单令牌（旧 {@code {$Request.token}}；页面没有表单时为空串）
 */
public record ConfigPage(AdminHead head, LegacyLang lang, Map<String, PhpValue> roots, LegacyAdminUrl urls, String token)
        implements LegacyView {
}
