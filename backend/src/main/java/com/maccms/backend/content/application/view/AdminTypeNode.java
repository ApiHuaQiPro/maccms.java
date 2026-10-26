package com.maccms.backend.content.application.view;

import java.util.List;

/**
 * 分类树的一个顶级节点（旧 {@code mac_list_to_tree($list, 'type_id', 'type_pid')}，application/common.php:476-500）：顶级分类与它的直属
 * 子分类（旧版只把上级存在的分类挂到上级下；上级不存在的子分类不出现）。
 *
 * @param count    分类列表页显示的数据数（旧 {@code $vo.cc}）；其他页面为 0
 * @param children 子分类与各自的数量
 */
public record AdminTypeNode(AdminRow row, long count, List<AdminTypeNode> children) {

    public AdminTypeNode {
        children = List.copyOf(children);
    }
}
