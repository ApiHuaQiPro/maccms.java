/*
 * 评论、留言表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/{comment,gbook}/info.html 末尾的内联脚本：内容为空时提示。字段名与提示文字改由本 script 标签的
 * data-field、data-msg 属性提供；逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var FIELD = self.getAttribute('data-field');
    var MSG = self.getAttribute('data-msg');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        var rules = {};
        rules[FIELD] = function (value) {
            if (value == "") {
                return MSG;
            }
        };
        form.verify(rules);


    });
})();
