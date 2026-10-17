package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 下载地址页（旧 vod/downer，B05）。地址只在权限与下载密码检查都通过后出现：可作为链接的（http/https、站内路径）输出为
 * {@link #link}；磁力、迅雷等其他协议只作为文本 {@link #text}（不生成链接）；不能输出时为固定提示 {@link #notice}。
 *
 * @param chrome     页面外壳
 * @param breadcrumb 首页 → 分类链 → 视频详情 → 下载 → 当前集
 * @param title      h1：视频名
 * @param episode    分组与集名
 * @param link       下载链接，可无
 * @param text       下载地址文本（不是链接），可为空串
 * @param notice     不能输出地址时的固定提示，可为空串
 * @param downUrl    下载页（全部分组）
 */
public record DownerPage(PageChrome chrome, List<NavLink> breadcrumb, String title, String episode,
                         Optional<SafeUrl> link, String text, String notice, SafeUrl downUrl) {
}
