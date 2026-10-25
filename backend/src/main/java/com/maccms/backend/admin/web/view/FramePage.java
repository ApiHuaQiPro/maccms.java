package com.maccms.backend.admin.web.view;

import com.maccms.backend.admin.application.view.MenuGroupView;
import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/** 框架页（旧 admin/view/index/index.html）。各地址都是旧 {@code url()} 的结果。 */
public record FramePage(AdminHead head, LegacyLang lang, List<MenuGroupView> menus, String clearUrl, String logoutUrl,
                        String welcomeUrl, String checkcacheUrl) {
}
