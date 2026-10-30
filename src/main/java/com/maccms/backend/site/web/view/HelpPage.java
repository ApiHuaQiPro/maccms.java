package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 帮助页（B06，旧 {@code help/index}，16 文档第 4.4 节）。旧模板缺失（D9-a），新版为固定结构：站名与站内入口链接，不回显请求参数。
 *
 * @param chrome   页面外壳
 * @param siteName 站名
 * @param links    站内入口（搜索、地图、RSS）
 */
public record HelpPage(PageChrome chrome, String siteName, List<NavLink> links) {

    public HelpPage {
        links = List.copyOf(links);
    }
}
