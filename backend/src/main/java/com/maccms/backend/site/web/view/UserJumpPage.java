package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 旧 ThinkPHP {@code success()}/{@code error()} 的非 ajax 提示页（{@code thinkphp/tpl/dispatch_jump.tpl}）：只给提示与链接，不自动跳转
 * （旧模板用脚本倒计时跳转；新版页面不带脚本）。
 *
 * @param url 去处；旧版为 {@code javascript:history.back(-1)} 等无法安全输出的地址时为空
 */
public record UserJumpPage(PageChrome chrome, boolean success, String message, Optional<SafeUrl> url) {
}
