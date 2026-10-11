package com.maccms.spike.thymeleaf.model;

import java.util.List;

/** 最小页面外壳的视图模型：站点、导航、SEO、标题、卡片列表。 */
public record ShellPage(SiteSettings site, List<NavItem> nav, PageMeta meta, String heading, List<VideoCard> cards) {
}
