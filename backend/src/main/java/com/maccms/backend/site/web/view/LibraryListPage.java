package com.maccms.backend.site.web.view;

import java.util.List;
import java.util.Optional;

/**
 * 文章、专题、演员、角色、网址、剧情的列表页（旧 art/index、art/type、topic/index、actor/index、actor/type、role/index、
 * website/index、website/type、plot/index、vod/role，以及 B05 的 show/search 结果，15 文档第 5、7 节）。
 *
 * @param chrome     页面外壳
 * @param pageClass  页面根元素的语义 class（article-page、topic-page、actor-page、role-page、website-page、plot-page）
 * @param siteKind   data-site-page 的值
 * @param heading    h1
 * @param breadcrumb 面包屑（最后一项 current）
 * @param categories 该模块的分类导航（当前分类标记 current），没有分类的模块为空
 * @param intro      列表前的说明（如 vod/role 的所属视频），可为空串
 * @param cards      当前页的卡片
 * @param empty      没有内容时的提示
 * @param pagination 多于一页时的分页
 */
public record LibraryListPage(PageChrome chrome, String pageClass, String siteKind, String heading,
                              List<NavLink> breadcrumb, List<NavLink> categories, String intro, List<ContentCard> cards,
                              String empty, Optional<PaginationView> pagination) {
}
