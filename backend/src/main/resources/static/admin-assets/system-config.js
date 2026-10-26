/*
 * 网站参数配置页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。
 * 逐行取自旧 application/admin/view/system/config.html:593-680 的内联脚本；上传地址与语言包文字改由本 script 标签的 data-* 属性提供，
 * "测试连接"按钮的 onclick="test_cache()" 改为在这里按 id 绑定（内容安全策略不允许内联事件）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var TEST_CACHE_URL = self.getAttribute('data-test-cache-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var MSG_WAIT = self.getAttribute('data-msg-wait');
    var MSG_TEST_ERR = self.getAttribute('data-msg-test-err');

    layui.use(['form','upload', 'layer'], function(){
        // 操作对象
        var element = layui.element
            ,form = layui.form
            , layer = layui.layer
            , upload = layui.upload;

        form.on('radio(cache_type)',function(data){
            $('.row_cache_server').hide();
           if(data.value=='memcache' || data.value=='redis' || data.value=='memcached'){
               $('.row_cache_server').show();
           }
        });

        element.on('tab(tb1)', function(){
            $.cookie('config_tab', this.getAttribute('lay-id'));
        });

        if( $.cookie('config_tab') !=null ) {
            element.tabChange('tb1', $.cookie('config_tab'));
        }

        upload.render({
            elem: '.layui-upload'
            ,url: UPLOAD_URL + "?flag=site"
            ,method: 'post'
            ,before: function(input) {
                layer.msg(MSG_UPLOADING, {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                    return false;
                }
                layer.closeAll();
                var input = $(obj).parent().parent().find('.upload-input');
                if ($(obj).attr('lay-type') == 'image') {
                    input.siblings('img').attr('src', res.data.file).show();
                }
                input.val(res.data.file);

                if(res.data.thumb_class !=''){
                    $('.'+ res.data.thumb_class).val(res.data.thumb[0].file);
                }
            }
        });

        $('.upload-input').hover(function (e){
            var e = window.event || e;
            var imgsrc = $(this).val();
            if(imgsrc.trim()==""){ return; }
            var left = e.clientX+document.body.scrollLeft+20;
            var top = e.clientY+document.body.scrollTop+20;
            $(".showpic").css({left:left,top:top,display:""});
            if(imgsrc.indexOf('://')<0){ imgsrc = ROOT_PATH + '/' + imgsrc; } else{ imgsrc = imgsrc.replace('mac:','http:'); }
            $(".showpic_img").attr("src", imgsrc);
        },function (e){
            $(".showpic").css("display","none");
        });


    });

    function test_cache(){
        var type = $("input[name='app[cache_type]']:checked").val();
        var host = $("input[name='app[cache_host]']").val();
        var port = $("input[name='app[cache_port]']").val();
        var user_name =  $("input[name='app[cache_username]']").val();
        var password = $("input[name='app[cache_password]']").val();
        layer.msg(MSG_WAIT,{time:500000});
        $.ajax({
            url: TEST_CACHE_URL,
            type: "post",
            dataType: "json",
            data: {type:type,host:host,port:port,username:user_name,password:password},
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

    $(document).on('click', '#btn_test_cache', function () {
        test_cache();
    });
})();
