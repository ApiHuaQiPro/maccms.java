package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 播放/下载列表中的一集（B05）。只有名称与站内页面链接，不含播放/下载地址。
 *
 * @param label   集名（解码一次）
 * @param url     该集的播放页或下载地址页（站内路径）
 * @param current 是否为当前集（模板输出 aria-current="page"）
 */
public record EpisodeLink(String label, SafeUrl url, boolean current) {
}
