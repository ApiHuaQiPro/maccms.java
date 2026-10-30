package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 我的权限页（{@code user/popedom}，B10，19 文档第 4.8 节）：全部分类按树显示，每格"是/否"；非视频分类只有列表页、内容页两列
 * （其余格为空串）。
 *
 * @param shell     会员页外壳
 * @param groupName 有效会员组名称
 * @param headers   列名：分类、列表页、内容页、播放页、下载页、试看
 * @param rows      分类行
 * @param back      返回会员中心
 */
public record UserPopedomPage(MemberShell shell, String groupName, List<String> headers, List<Row> rows,
                              NavLink back) {

    public UserPopedomPage {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }

    /** 一个分类：{@code child} 为二级分类；{@code cells} 与表头的 5 个权限列对应。 */
    public record Row(String name, boolean child, List<String> cells) {

        public Row {
            cells = List.copyOf(cells);
        }
    }
}
