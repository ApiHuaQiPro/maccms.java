package com.maccms.backend.site.web.view;

import java.util.List;
import java.util.Optional;

/**
 * 分类列表页（旧 vod/type，数字 id）。
 *
 * @param chrome        页面外壳
 * @param heading       h1（分类名）
 * @param breadcrumb    首页 → 上级分类 → 当前分类（最后一项 current）
 * @param subcategories 可见的直接子分类
 * @param videos        当前页的视频；为空时页面显示"该分类暂无内容"（只会出现在第 1 页）
 * @param pagination    多于一页时的分页
 */
public record CategoryPage(PageChrome chrome, String heading, List<NavLink> breadcrumb, List<NavLink> subcategories,
                           List<VideoCardView> videos, Optional<PaginationView> pagination) {
}
