package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 访问密码页（B05，D-B05-3，403，no-store）：原生表单 POST 到同一地址，没有客户端密码处理；未配置票据密钥时没有表单，只说明暂不支持
 * 在线验证。播放器内页（iframe）中的密码页见 {@link FramePasswordPage}。
 *
 * @param chrome     页面外壳
 * @param heading    标题
 * @param message    说明
 * @param formAction 表单提交地址（本页的规范地址），未启用在线验证时为空
 * @param error      上次提交的错误提示，可为空串
 * @param back       返回链接，可无
 */
public record PasswordPage(PageChrome chrome, String heading, String message, Optional<SafeUrl> formAction,
                           String error, Optional<SafeUrl> back) {
}
