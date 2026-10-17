package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 文章、专题、演员、角色、网址、剧情的筛选页（旧 show）与搜索页（旧 search，B05 D-B05-5，15 文档第 7.2 节）。只有公开摘要字段（内容卡片）
 * 与安全 URL；卡片、筛选组、分页沿用既有 class（content-grid/content-card、filter-group/filter-chip、pagination），没有新组件。
 *
 * @param chrome       页面外壳（noindex；参数不合法时没有 canonical）
 * @param pageClass    页面根元素的语义 class（article-page、topic-page、actor-page、role-page、website-page、plot-page）
 * @param siteKind     data-site-page 的值（如 art-search、art-show）
 * @param heading      h1
 * @param breadcrumb   面包屑（最后一项 current）
 * @param categories   筛选页的分类导航（当前分类标记 current）；搜索页为空
 * @param invalid      参数不合法（400）：固定提示与空白表单，不回显任何输入
 * @param formAction   搜索表单提交地址（GET；筛选页为该分类的筛选页基址）
 * @param formLabel    表单的无障碍名称
 * @param keyword      表单回显的关键字（已校验），无关键字时为空串
 * @param hiddenFields 提交关键字时保留的当前条件（不含 wd、page）
 * @param total        有结果查询时的总数；搜索页无条件、参数不合法时没有
 * @param filters      筛选与排序链接组
 * @param cards        当前页的卡片
 * @param empty        有查询而没有内容时的提示
 * @param pagination   多于一页时的分页
 */
public record ModuleListView(PageChrome chrome, String pageClass, String siteKind, String heading,
                             List<NavLink> breadcrumb, List<NavLink> categories, boolean invalid, SafeUrl formAction,
                             String formLabel, String keyword, List<HiddenField> hiddenFields, Optional<Long> total,
                             List<FilterGroup> filters, List<ContentCard> cards, String empty,
                             Optional<PaginationView> pagination) {
}
