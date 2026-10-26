/*
 * 友情链接表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/link/info.html:54-75 的内联脚本；语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 * 旧表单里"类型"下拉框的 selected 判断写成了未定义的 $vo（应为 $info），null eq 0 为真，所以总是选中"文字链接"；新版照旧
 * （22 文档第 7.8 节）。
 */
(function () {
    var self = document.currentScript;
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_URL = self.getAttribute('data-msg-url');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            link_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            link_url: function (value) {
                if (value == "") {
                    return MSG_URL;
                }
            }
        });


    });
})();
