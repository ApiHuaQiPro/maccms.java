/*
 * 安装向导第三步的两个表单（B22，docs/architecture/31-b22-install-wizard.md 第 7 节）：取自旧 application/install/view/index/step3.html:105-137
 * 的内联脚本，提示文字经 data-* 传入。"测试数据库连接"以 Ajax 提交第一个表单，返回 code=1 后才允许提交第二个表单。
 */
(function () {
    var self = document.currentScript;
    var waitText = self.getAttribute('data-wait');
    var submitTip = self.getAttribute('data-submit-tip');
    var test = 0;
    layui.define(['element', 'form'], function (exports) {
        var $ = layui.jquery, layer = layui.layer, form = layui.form;
        form.on('submit(formTest)', function (data) {
            var _form = '';
            if ($(this).attr('data-form')) {
                _form = $($(this).attr('data-form'));
            } else {
                _form = $(this).parents('form');
            }

            layer.msg(waitText, {time: 500000});
            $.ajax({
                type: "POST",
                url: _form.attr('action'),
                data: _form.serialize(),
                dataType: 'json',
                success: function (res) {
                    if (res.code == 1) {
                        test = 1;
                    }
                    layer.msg(res.msg);
                }
            });
            return false;
        });
        form.on('submit(formSubmit)', function (data) {
            if (test == 0) {
                layer.msg(submitTip);
                return false;
            }
        });
    });
})();
