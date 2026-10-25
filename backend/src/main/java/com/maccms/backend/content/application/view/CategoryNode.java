package com.maccms.backend.content.application.view;

import java.util.List;

/**
 * 分类树的一个节点（B10，会员中心"我的权限"页；旧 type_tree 的一项）。不是 OpenAPI 的一部分。
 *
 * @param mid 模型（1 视频、2 文章、8 演员、11 网址……）
 */
public record CategoryNode(int id, String name, int mid, List<CategoryNode> children) {

    public CategoryNode {
        children = List.copyOf(children);
    }
}
