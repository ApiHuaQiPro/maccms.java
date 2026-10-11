package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 首页的一个视频区块。没有视频的区块不出现在页面模型里。
 *
 * @param heading 区块标题
 * @param moreUrl "更多"链接（分类区块指向分类页），可无
 * @param videos  卡片，至少一个
 */
public record VideoSection(String heading, Optional<SafeUrl> moreUrl, List<VideoCardView> videos) {
}
