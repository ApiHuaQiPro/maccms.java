package com.maccms.backend.legacyconfig.application;

/**
 * 可以读取的旧配置文件（相对旧站 {@code application/} 目录）。只列出由 {@code mac_arr2file} 写出或随旧站分发的
 * {@code var_export}/数组字面量文件；{@code database.php}、{@code config.php} 等含凭证或含代码的文件不在此列，也读不到。
 */
public enum LegacyConfigFile {
    /** 系统配置（后台"系统"各组，System.php 写回）。 */
    MACCMS("extra/maccms.php"),
    /** 路由（System::configurl 写回，System.php:231）。 */
    ROUTE("route.php"),
    /** 采集分类绑定（Collect.php:194,222）。 */
    BIND("extra/bind.php"),
    /** 站群（Domain.php）。 */
    DOMAIN("extra/domain.php"),
    /** 后台快捷菜单（admin/controller/Index.php:123）。 */
    QUICKMENU("extra/quickmenu.php"),
    /** 定时任务（admin/controller/Timming.php）。 */
    TIMMING("extra/timming.php"),
    /** 播放器（Vodplayer.php）。 */
    VODPLAYER("extra/vodplayer.php"),
    /** 下载器（Voddowner.php）。 */
    VODDOWNER("extra/voddowner.php"),
    /** 服务器组（Vodserver.php）。 */
    VODSERVER("extra/vodserver.php"),
    /** 插件钩子（fastadmin-addons 写回）。 */
    ADDONS("extra/addons.php"),
    /** 版本信息（手写，只读）。 */
    VERSION("extra/version.php"),
    /** 验证码参数（手写，只读）。 */
    CAPTCHA("extra/captcha.php"),
    /** 队列参数（手写，只读）。 */
    QUEUE("extra/queue.php");

    private final String relativePath;

    LegacyConfigFile(String relativePath) {
        this.relativePath = relativePath;
    }

    public String relativePath() {
        return relativePath;
    }
}
