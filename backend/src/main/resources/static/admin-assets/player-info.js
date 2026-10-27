/*
 * 播放器、服务器组、下载器表单页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧
 * application/admin/view/vodplayer/info.html、vodserver/info.html、voddowner/info.html 末尾的内联脚本（三页相同）；语言包文字改由本
 * script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var MSG_CODE = self.getAttribute('data-msg-code-empty');
    var MSG_NAME = self.getAttribute('data-msg-name-empty');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            from: function (value) {
                if (value == "") {
                    return MSG_CODE;
                }
            },
            show: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            }
        });


    });
})();
