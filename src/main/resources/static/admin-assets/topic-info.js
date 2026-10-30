/*
 * 专题表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/topic/info.html:247-322 的内联脚本；上传地址与语言包文字改由本 script 标签的 data-* 属性提供。
 * 除此之外逻辑不变（rndNum 由旧 static/js/admin_common.js 提供）。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_TPL = self.getAttribute('data-msg-tpl');

    layui.use(['form','upload', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                , upload = layui.upload;;

        // 验证
        form.verify({
            topic_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            topic_tpl: function (value) {
                if (value == "") {
                    return MSG_TPL;
                }
            }
        });

        upload.render({
            elem: '.layui-upload'
            ,url: UPLOAD_URL
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
            if(imgsrc.indexOf('://')<0){ imgsrc = ROOT_PATH + '/' + imgsrc;	} else{ imgsrc = imgsrc.replace('mac:','http:'); }
            $(".showpic_img").attr("src", imgsrc);
        },function (e){
            $(".showpic").css("display","none");
        });

        $("#btn_rnd").click(function(){
            $("#topic_hits").val( rndNum(5000,9999) );
            $("#topic_hits_month").val( rndNum(1000,4999) );
            $("#topic_hits_week").val( rndNum(300,999) );
            $("#topic_hits_day").val( rndNum(1,299) );
            $("#topic_up").val( rndNum(1,999) );
            $("#topic_down").val( rndNum(1,999) );
            $("#topic_score").val( rndNum(10) );
            $("#topic_score_all").val( rndNum(1000) );
            $("#topic_score_num").val( rndNum(100) );
        });


        var ue = editor_getEditor('topic_content');

    });
})();
