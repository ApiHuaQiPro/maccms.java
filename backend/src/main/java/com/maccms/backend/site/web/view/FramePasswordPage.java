package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 播放器内页中的播放密码页（旧 vod/player_pwd，B05）。规则同 {@link PasswordPage}，外壳为精简外壳，没有脚本。
 *
 * @param frame      精简外壳
 * @param heading    标题
 * @param message    说明
 * @param formAction 表单提交地址，未启用在线验证时为空
 * @param error      上次提交的错误提示，可为空串
 */
public record FramePasswordPage(PlayerFrame frame, String heading, String message, Optional<SafeUrl> formAction,
                                String error) {
}
