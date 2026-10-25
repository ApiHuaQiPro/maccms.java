package com.maccms.backend.admin.application;

import com.maccms.backend.common.captcha.CaptchaSettings;
import com.maccms.backend.common.lang.LegacyLang;

/**
 * 后台用到的旧配置（启动时读一次，21 文档第 7 节）。
 *
 * @param loginVerify 旧 {@code app.admin_login_verify != '0'}（Admin.php:118，login.html:40）：宽松比较，{@code '0'}、{@code 0}、
 *                    {@code '00'}、{@code false} 等于 {@code '0'}，空串与缺失<b>不</b>等于（要验证码）
 * @param pageSize    旧 {@code app.pagesize}（Base.php:28），按 {@code intval}，小于 1 时为 20（ThinkPHP 分页的默认值）
 * @param lang        旧 {@code app.lang} 选出的语言包（{@link LegacyLang#select}）
 * @param versionCode 旧 {@code extra/version.php} 的 {@code code}（样式表地址的版本参数，head.html:8）
 * @param captcha     验证码参数（旧 {@code extra/captcha.php}，与前台相同）
 */
public record AdminSettings(boolean loginVerify, int pageSize, LegacyLang lang, String versionCode,
                            CaptchaSettings captcha) {
}
