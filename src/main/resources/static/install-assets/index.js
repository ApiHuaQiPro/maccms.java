/*
 * 安装向导首页的语言包选择（B22，docs/architecture/31-b22-install-wizard.md 第 7 节）：取自旧 application/install/view/index/index.html:31-40
 * 的内联脚本，地址经 data-url 传入。
 */
(function () {
    var url = document.currentScript.getAttribute('data-url');
    layui.define(['element', 'form'], function (exports) {
        var form = layui.form;
        form.on('select(lang)', function (data) {
            if (data.value != '') {
                location.href = url + "?lang=" + (data.value);
            }
        });
    });
})();
