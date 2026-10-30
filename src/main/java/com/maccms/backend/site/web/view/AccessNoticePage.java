package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 访问受限的固定提示页（B05，403/429，no-store）：无权限、需要积分、版权限制、尝试过于频繁。只有固定文案与返回链接，
 * 不输出受限地址、积分数或内部字段。
 *
 * @param chrome  页面外壳
 * @param heading 标题
 * @param message 固定说明
 * @param back    返回链接（视频详情页等），可无
 */
public record AccessNoticePage(PageChrome chrome, String heading, String message, Optional<SafeUrl> back) {
}
