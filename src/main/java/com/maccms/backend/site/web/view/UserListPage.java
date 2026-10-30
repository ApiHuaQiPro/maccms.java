package com.maccms.backend.site.web.view;

import java.util.List;
import java.util.Optional;

/**
 * 会员中心的列表页（B10，{@code pages/userList.jte}）：播放/下载/收藏/全部记录、积分日志、推广会员、订单、充值卡。
 *
 * @param shell    会员页外壳（nonce 与 member.js）
 * @param pageKey  {@code data-site-page} 的值（如 {@code user-plays}）
 * @param heading  h1
 * @param intro    表格前的说明段落（可为空）
 * @param tabs     筛选链接（模型、类型、推广层级；当前项 {@code aria-current}）
 * @param table    表格
 * @param clearAll 页头的"清空"表单（可无）
 * @param back     返回会员中心
 */
public record UserListPage(MemberShell shell, String pageKey, String heading, List<String> intro, List<NavLink> tabs,
                           UserTable table, Optional<MemberAction> clearAll, NavLink back) {

    public UserListPage {
        intro = List.copyOf(intro);
        tabs = List.copyOf(tabs);
    }
}
