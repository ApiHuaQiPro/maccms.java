package com.maccms.backend.admin.web.view;

/**
 * 旧 admin/view/public/head.html 用到的值。
 *
 * @param sitePath  旧 {@code __ROOT__}（部署前缀，根目录部署为空串）
 * @param adminPath 旧 {@code $_SERVER['SCRIPT_NAME']}（部署前缀 + 入口）
 * @param version   旧 {@code $MAC_VERSION}（extra/version.php 的 code，样式与脚本地址的版本参数）
 */
public record AdminHead(String title, String sitePath, String adminPath, String version) {

    /** 旧 {@code __STATIC__}。 */
    public String staticPath() {
        return sitePath + "/static";
    }

    /** 新版的页面脚本目录（旧模板里的内联脚本移到这里，21 文档第 8 节）。 */
    public String assetsPath() {
        return sitePath + "/admin-assets";
    }
}
