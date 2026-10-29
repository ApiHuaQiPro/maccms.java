package com.maccms.backend.install.web.view;

/**
 * 非 Ajax 请求的成功/失败提示页（旧 ThinkPHP {@code dispatch_jump.tpl}，安装模块用框架默认模板，application/config.php:148-149）。
 * 文字是模板里写死的简体，不随语言包；{@code msg} 已按旧版 {@code strip_tags} 去掉标签。
 *
 * @param success    旧 {@code $code == 1}
 * @param url        跳转地址；失败时为 {@link #BACK}
 * @param assetsPath 页面脚本目录（倒计时脚本 jump.js）
 */
public record JumpPage(boolean success, String msg, String url, int waitSeconds, String assetsPath) {

    /** 旧版失败时的默认地址（thinkphp/library/traits/controller/Jump.php:69）。 */
    public static final String BACK = "javascript:history.back(-1);";

    /** 地址是 {@link #BACK}：模板写成固定文字（jte 不输出 {@code href} 里以 {@code javascript:} 开头的动态值）。 */
    public boolean back() {
        return BACK.equals(url);
    }
}
