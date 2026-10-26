/*
 * 会员表单的页面脚本（B14，docs/architecture/23-b14-admin-member.md 第 8 节）。
 * 逐行取自旧 application/admin/view/user/info.html:116-203 的内联脚本；上传地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外
 * 逻辑不变（旧版的 user_pwd 必填校验没有挂到任何输入框上，照旧不生效）。头像上传的 flag=user 在新版后台被拒绝（22 文档 D-B13-17），
 * 按钮照旧显示，点了得到"文件上传失败！"的提示，头像地址仍可手工填写（23 文档第 8.2 节）。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_PWD = self.getAttribute('data-msg-pwd');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    layui.use(['form', 'layer', 'upload',  'laydate'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
             ,laydate = layui.laydate
            ,upload = layui.upload;


        // 验证
        form.verify({
            user_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            user_pwd: function (value) {
                if (value == "") {
                    return MSG_PWD;
                }
            }
        });

        //日期范围
        laydate.render({
            elem: '#user_end_time',
            type: 'datetime'
        });
        laydate.render({
            elem: '#user_start_time',
            type: 'datetime'
        });

        form.on('select(group_id)', function(data) {
            $('.rowTime').hide();
            if(data.value >2 ){
                $('.rowTime').show();
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
            $(".showpic_img").attr("src", imgsrc + '?r=' + Math.random() );
        },function (e){
            $(".showpic").css("display","none");
        });


    });
})();
