/*
 * 生成充值卡表单的页面脚本（B14，docs/architecture/23-b14-admin-member.md 第 8 节）。
 * 逐行取自旧 application/admin/view/card/info.html:52-79 的内联脚本；语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var MSG_NUM = self.getAttribute('data-msg-num');
    var MSG_MONEY = self.getAttribute('data-msg-money');
    var MSG_POINT = self.getAttribute('data-msg-point');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            num: function (value) {
                if (value == "") {
                    return MSG_NUM;
                }
            },
            money: function (value) {
                if (value == "") {
                    return MSG_MONEY;
                }
            },
            point: function (value) {
                if (value == "") {
                    return MSG_POINT;
                }
            }
        });


    });
})();
