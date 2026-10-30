package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 文章、专题、演员、角色、网址、剧情列表中的一张卡片（B05；按 MACC-54 的约定由页面内联输出 {@code article.content-card}，
 * 不是组件）。
 *
 * @param title 名称（解码一次）
 * @param meta  一行说明（备注、分类、演员等，解码一次），可为空串
 * @param url   详情页地址
 * @param image 图片（经 ImageUrlNormalizer），没有时显示占位
 */
public record ContentCard(String title, String meta, SafeUrl url, Optional<SafeUrl> image) {
}
