package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;

/**
 * 内容发布管理（旧 admin/view/cj/publish.html）。
 *
 * @param id     参数 {@code id}（旧版原样写进链接，新版转义）
 * @param status 参数 {@code status}（缺失为空串）
 * @param curUrl 分页地址（旧 {@code url('cj/publish', $param)}，页码与条数是占位符）
 */
public record CjPublishView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list, String id, String status, String curUrl)
        implements BrowserText {

    /** 旧 {@code $param.status eq 'x'}。 */
    public boolean tab(String s) {
        return status.equals(s);
    }

    /** 旧状态列：1 未采集、2 已采集，其他已发布。 */
    public String statusText(AdminRow vo) {
        return switch (vo.s("status")) {
            case "1" -> lang.get("admin/cj/collected_not");
            case "2" -> lang.get("admin/cj/collected");
            default -> lang.get("admin/cj/published");
        };
    }
}
