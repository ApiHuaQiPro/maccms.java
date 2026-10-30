package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/**
 * 管理员列表页（旧 admin/view/admin/index.html）。
 *
 * @param curUrl 分页地址（旧 {@code url('admin/index', $param)}，页码与条数是 {@code {page}}、{@code {limit}} 的 urlencode 占位）
 */
public record AdminListView(AdminHead head, LegacyLang lang, List<Item> rows, long total, int limit, long page,
                            String curUrl, String infoUrl, String delUrl) {

    /**
     * 一行。
     *
     * @param lastLoginTime {@code mac_day(t,'color')} 的时间文字（0 为空串）
     * @param today         时间是今天（旧版包一层 {@code <font color="#FF0000">}）
     * @param self          是当前登录的管理员（旧版不显示复选框与删除）
     */
    public record Item(long id, String name, int status, String lastLoginTime, boolean today, String lastLoginIp,
                       long loginNum, boolean self, String fieldUrl, String infoUrl, String delUrl) {
    }
}
