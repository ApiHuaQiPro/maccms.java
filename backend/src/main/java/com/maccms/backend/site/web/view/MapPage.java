package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 地图页（B06，旧 {@code map/index}，16 文档第 4.1 节）。旧模板缺失（D9-a），结构由后端定义：各模型的可见分类树与站内入口链接，不列内容条目。
 *
 * @param chrome   页面外壳
 * @param heading  标题
 * @param sections 各模型的分类树（某模型没有可见分类时不出现）
 * @param links    站内入口（专题/演员/角色/网址首页、RSS 各格式）
 */
public record MapPage(PageChrome chrome, String heading, List<Section> sections, List<NavLink> links) {

    public MapPage {
        sections = List.copyOf(sections);
        links = List.copyOf(links);
    }

    /**
     * @param id    区块 id（{@code map-vod}、{@code map-art}、{@code map-actor}、{@code map-website}），标题的 id 与列表的
     *              aria-labelledby 都用它
     * @param title 区块标题
     * @param nodes 顶级分类
     */
    public record Section(String id, String title, List<Node> nodes) {
        public Section {
            nodes = List.copyOf(nodes);
        }
    }

    /** 分类树的一项。 */
    public record Node(NavLink link, List<Node> children) {
        public Node {
            children = List.copyOf(children);
        }
    }
}
