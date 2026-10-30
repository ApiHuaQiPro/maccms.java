package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 视频卡片（site/components/videoCard.jte 的参数）。
 *
 * @param title     视频名（vod_name，解码一次）
 * @param remarks   备注（vod_remarks，解码一次），可为空串
 * @param detailUrl 详情页地址
 * @param imageUrl  海报（vod_pic 经 ImageUrlNormalizer），不合法或为空时没有，模板显示占位
 */
public record VideoCardView(String title, String remarks, SafeUrl detailUrl, Optional<SafeUrl> imageUrl) {
}
