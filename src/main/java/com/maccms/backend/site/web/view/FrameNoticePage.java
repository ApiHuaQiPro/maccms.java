package com.maccms.backend.site.web.view;

/**
 * 播放器内页中的固定提示（B05）：无权限、需要积分、版权限制，或 W1/W2 关闭、地址无法使用。没有脚本。
 *
 * @param frame   精简外壳
 * @param heading 标题
 * @param message 固定说明
 */
public record FrameNoticePage(PlayerFrame frame, String heading, String message) {
}
