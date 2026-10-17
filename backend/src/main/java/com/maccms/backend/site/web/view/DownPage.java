package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 下载页（旧 vod/down，B05）：下载分组与各集，每集链接到下载地址页（vod/downer）。本页不含下载地址。
 *
 * @param chrome     页面外壳
 * @param breadcrumb 首页 → 分类链 → 视频详情 → 下载
 * @param title      h1：视频名
 * @param sources    全部下载分组
 * @param detailUrl  视频详情页
 */
public record DownPage(PageChrome chrome, List<NavLink> breadcrumb, String title, List<PlaySourceView> sources,
                       SafeUrl detailUrl) {
}
