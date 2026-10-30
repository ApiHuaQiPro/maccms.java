package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRowPage;

/**
 * 友情链接列表（旧 admin/view/link/index.html）。
 *
 * @param wd     搜索框的值（旧 {@code {$param['wd']|mac_filter_xss}}：浏览器看到的文字）
 * @param curUrl 分页地址（旧 {@code url('link/index', $param)}，页码与条数为占位）
 */
public record LinkListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list, String wd,
                           String curUrl) implements BrowserText {
}
