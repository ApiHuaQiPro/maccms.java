/*
 * 邮件发送配置页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧 application/admin/view/system/configemail.html:121-162
 * 的内联脚本；地址与语言包文字改由本 script 标签的 data-* 属性提供，"发送测试邮件"按钮的 onclick="test_email()" 改为在这里按 id 绑定。
 * 除此之外逻辑不变。测试发信（E07）在 B25 实现，此前服务端返回"由 B25 批次提供"的提示。
 */
(function () {
    var self = document.currentScript;
    var TEST_URL = self.getAttribute('data-test-url');
    var MSG_WAIT = self.getAttribute('data-msg-wait');
    var MSG_TEST_ERR = self.getAttribute('data-msg-test-err');
    layui.use(['element', 'form', 'layer'], function() {
        var element = layui.element
            ,form = layui.form
            , layer = layui.layer;


        element.on('tab(tb1)', function(){
            $.cookie('configemail_tab', this.getAttribute('lay-id'));
        });

        if( $.cookie('configemail_tab') !=null ) {
            element.tabChange('tb1', $.cookie('configemail_tab'));
        }

    });
    function test_email() {
        var type = $('#ac').val();
        var test = $("#test").val();
        var nick = $("#nick").val();

        layer.msg(MSG_WAIT,{time:500000});
        $.ajax({
            url: TEST_URL,
            type: "post",
            dataType: "json",
            data: {type:type,nick:nick,test:test},
            beforeSend: function () {
            },
            error:function(r){
                layer.msg(MSG_TEST_ERR,{time:1800});
            },
            success: function (r) {
                layer.msg(r.msg,{time:1800});
            },
            complete: function () {
            }
        });
    }

    $(document).on('click', '#btn_test_email', function () {
        test_email();
    });
})();
