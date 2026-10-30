package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 首页（旧 index/index）。
 *
 * @param chrome     页面外壳
 * @param heading    h1（站名）
 * @param categories 顶级分类（分类网格）
 * @param sections   有内容的视频区块；为空时页面显示"暂无内容"
 */
public record HomePage(PageChrome chrome, String heading, List<NavLink> categories, List<VideoSection> sections) {
}
