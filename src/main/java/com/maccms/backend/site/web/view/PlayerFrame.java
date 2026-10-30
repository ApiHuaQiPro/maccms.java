package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 同源播放器内页（旧 vod/player）的精简外壳（B05）：只有标题、样式表与 noindex，没有站点导航。
 *
 * @param title      &lt;title&gt; 文本
 * @param stylesheet 站点样式表
 */
public record PlayerFrame(String title, SafeUrl stylesheet) {
}
