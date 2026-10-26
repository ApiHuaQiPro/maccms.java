/*
 * 后台登录页的页面脚本（B12，docs/architecture/21-b12-admin-framework.md 第 8 节）。
 * 逐行取自旧 application/admin/view/index/login.html:65-128 的内联脚本与验证码图片的 onclick；为了内容安全策略不允许内联脚本，移到这里，
 * 模板里由服务端写入的地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var LOGIN_URL = self.getAttribute('data-login-url');
    var INDEX_URL = self.getAttribute('data-index-url');
    var MSG_NO = self.getAttribute('data-msg-no');
    var MSG_PASS = self.getAttribute('data-msg-pass');
    var MSG_VERIFY = self.getAttribute('data-msg-verify');
    var MSG_WAIT = self.getAttribute('data-msg-wait');

    // B15：旧模板的 <style>body{background:url('{$background}')}（admin_login_init 钩子，adminloginbg 插件）；样式块里不能放数据，改由脚本设置
    var BG = self.getAttribute('data-background');
    if (BG) {
        document.body.style.backgroundImage = "url('" + BG.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "')";
    }

    // 旧版写在 <img onclick="this.src = this.src+'?'">
    var img = document.getElementById('verify_img');
    if (img) {
        img.addEventListener('click', function () {
            this.src = this.src + '?';
        });
    }

    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            admin_name: function (value) {
                if (value == "") {
                    return MSG_NO;
                }
            },
            admin_pwd: function (value) {
                if (value == "") {
                    return MSG_PASS;
                }
            },
            verify: function (value) {
                if (value == "") {
                    return MSG_VERIFY;
                }
            }
        });

        // 提交监听
        form.on('submit(sub)', function (data) {
            layer.msg(MSG_WAIT,{time:500000});
            $.post(LOGIN_URL,data.field,function(r){
                if(r.code==1){
                    location.href=INDEX_URL;
                }
                else{
                    layer.msg(r.msg,{time:1800});
                    $('#verify_img').click();
                }
            });
            return false;
        });


        $("input[name='admin_name']").bind('keypress',function(event){
            if(event.keyCode == "13") {
                if($("input[name='admin_name']").val()!=''){
                    $("input[name='admin_pwd']").focus();
                }
            }
        });

        $("input[name='admin_pwd']").bind('keypress',function(event){
            if(event.keyCode == "13") {
                if($("input[name='admin_pwd']").val()!=''){
                    $("input[name='verify']").focus();
                }
            }
        });

        $("input[name='verify']").bind('keypress',function(event){
            if(event.keyCode == "13") {
                if($("input[name='verify']").val()!=''){
                    $('.btn-submit').click();
                }
            }
        });
    });
})();
