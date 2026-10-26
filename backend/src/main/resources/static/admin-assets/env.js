/*
 * 后台页面的全局变量（B12，docs/architecture/21-b12-admin-framework.md 第 8 节）：旧 application/admin/view/public/head.html:11-13 的
 * 内联脚本 var ROOT_PATH="__ROOT__",ADMIN_PATH="{$_SERVER['SCRIPT_NAME']}", MAC_VERSION="v10"; 改由本 script 标签的 data-* 属性提供
 * （内容安全策略不允许内联脚本）。static/js/admin_common.js 原样复用，它读这两个全局变量。
 */
(function () {
    var self = document.currentScript;
    window.ROOT_PATH = self.getAttribute('data-root-path');
    window.ADMIN_PATH = self.getAttribute('data-admin-path');
    window.MAC_VERSION = 'v10';
})();
