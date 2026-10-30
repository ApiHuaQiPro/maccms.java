/*
 * 模板编辑页（B15，docs/architecture/24-b15-admin-system.md 第 7 节）。旧 template/info.html 用公共的 formSubmit（data-child="true"）：
 * 成功时刷新父页并关闭弹层——这里照旧；失败时旧版只弹出提示，新版把原因（编译错误含模板名与行号）显示在内容框上方，内容不丢，
 * 并换上响应里的新表单令牌（旧令牌已在服务端用掉），改好后可以直接再保存。"恢复上一版"按钮先确认，再以 op=restore 提交同一表单。
 */
(function () {
    var self = document.currentScript;
    var MSG_WAIT = self.getAttribute('data-msg-wait');

    layui.use(['form', 'layer'], function () {
        var form = layui.form,
            layer = layui.layer;

        form.on('submit(tplSubmit)', function (data) {
            var btn = $(data.elem);
            var op = btn.attr('data-op') || '';
            var confirmText = btn.attr('data-confirm');
            var go = function () {
                var f = $('#tplForm');
                $('#tplOp').val(op);
                layer.msg(MSG_WAIT, {time: 500000});
                $.ajax({
                    type: 'POST',
                    url: f.attr('action'),
                    data: f.serialize(),
                    dataType: 'json',
                    success: function (res) {
                        if (res.code == 1) {
                            layer.msg(res.msg, {time: 800}, function () {
                                parent.location.reload();
                            });
                            return;
                        }
                        layer.closeAll();
                        $('#tplError').text(res.msg).prop('hidden', false);
                        if (res.data && res.data.token) {
                            $("input[name='__token__']").val(res.data.token);
                        }
                        window.scrollTo(0, 0);
                    },
                    error: function () {
                        layer.closeAll();
                        $('#tplError').text('请求失败').prop('hidden', false);
                    }
                });
            };
            if (confirmText) {
                layer.confirm(confirmText, function (index) {
                    layer.close(index);
                    go();
                });
            } else {
                go();
            }
            return false;
        });
    });
})();
