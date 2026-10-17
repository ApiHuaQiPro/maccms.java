package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 版权提示页（B06，旧 {@code vod/copyright}，index/controller/Vod.php:74-78，16 文档第 4.5 节）。
 *
 * @param chrome  页面外壳
 * @param heading 标题：视频名；视频需要访问密码或详情因版权不展示时为固定的"版权提示"（不泄露受限详情）
 * @param notice  旧配置 {@code app.copyright_notice}
 * @param home    返回首页链接
 */
public record CopyrightPage(PageChrome chrome, String heading, String notice, SafeUrl home) {
}
