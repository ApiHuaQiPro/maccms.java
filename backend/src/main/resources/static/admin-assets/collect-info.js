/*
 * 采集源表单的页面脚本（B17，docs/architecture/25-b17-collect-config.md 第 7 节）。逐行取自旧 application/admin/view/collect/info.html:96-145
 * 的内联脚本；"测试"按钮的地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。新版的 collect/test 不连接资源站
 * （E01 只做本地版本），总是返回固定提示（code 不为 1），所以这里只会走 layer.msg(r.msg) 一支。
 */
(function () {
    var self = document.currentScript;
    var TEST_URL = self.getAttribute('data-test-url');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_URL = self.getAttribute('data-msg-url');
    var MSG_TEST_OK = self.getAttribute('data-msg-test-ok');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            collect_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            collect_url: function (value) {
                if (value == "") {
                    return MSG_URL;
                }
            }
        });


        $('#btnTest').click(function() {
            var that = $(this);
            var data = 'cjurl='+ $('#collect_url').val() + '&cjflag='+ '&ac=list';

            $.post(TEST_URL,data,function(r){
                if(r.code==1){
                    layer.msg( MSG_TEST_OK + '：'+ r.msg ,{time:1800});
                    if(r.msg=='json'){
                        $("input[name='collect_type'][value=2]").attr("checked",true);
                    }
                    else{
                        $("input[name='collect_type'][value=1]").attr("checked",true);
                    }
                    form.render('radio');
                }
                else{
                    layer.msg(r.msg,{time:1800});
                }
            });

        });

        form.on('radio(collect_mid)',function(data){
            $('.row_filer').hide();
            if(data.value=='1'){
                $('.row_filer').show();
            }
        });

    });
})();
