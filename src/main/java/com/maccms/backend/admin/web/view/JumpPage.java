package com.maccms.backend.admin.web.view;

/**
 * 非 Ajax 请求的成功/失败提示页（旧 ThinkPHP {@code dispatch_jump.tpl}，thinkphp/tpl/dispatch_jump.tpl；后台没有改用
 * admin/view/public/jump.html，Init.php:69-72 只对前台设置）。文字是模板里写死的简体，不随语言包。
 *
 * @param success 旧 {@code $code == 1}
 * @param url     跳转地址：成功时为来源页（Referer）或当前动作的地址，失败时为 {@code javascript:history.back(-1);}
 * @param assetsPath 新版页面脚本目录（倒计时脚本 jump.js）
 */
public record JumpPage(boolean success, String msg, String url, int waitSeconds, String assetsPath) {

    /** 旧版失败时的默认地址（Jump.php:69）。 */
    public static final String BACK = "javascript:history.back(-1);";

    /** 地址是 {@link #BACK}：模板写成固定文字（jte 不输出 {@code a@href} 里以 {@code javascript:} 开头的动态值）。 */
    public boolean back() {
        return BACK.equals(url);
    }
}
