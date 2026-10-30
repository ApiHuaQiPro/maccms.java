/*
 * 演员表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/actor/info.html:270-383 的内联脚本；上传与扩展字典地址、语言包文字、编辑时的分类编号改由本 script
 * 标签的 data-* 属性提供。扩展项按钮的文字在拼进 HTML 前转义（旧版原样拼接，扩展项含标签时会注入页面；显示不变）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var EXTEND_URL = self.getAttribute('data-extend-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var TYPE_ID = self.getAttribute('data-type-id');

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    layui.use(['form','upload', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                , upload = layui.upload;;

        // 验证
        form.verify({
            actor_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            }
        });

        $(document).on("click", ".extend", function(){
            var $id = $(this).attr('data-id');
            var $val;
            if($id == 'actor_class'||$id == 'actor_keywords'){
                $val = $("input[id='"+$id+"']").val();
                if($val!=''){
                    $val = $val+',';
                }
                if($val.indexOf($(this).text())>-1){
                    return;
                }
                $("input[id='"+$id+"']").val($val+$(this).text());
            }else{
                $("input[id='"+$id+"']").val($(this).text());
            }
        });

        form.on('select(type_id)', function(data){
            getExtend(data.value);
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
            $("#actor_hits").val( rndNum(5000,9999) );
            $("#actor_hits_month").val( rndNum(1000,4999) );
            $("#actor_hits_week").val( rndNum(300,999) );
            $("#actor_hits_day").val( rndNum(1,299) );
            $("#actor_up").val( rndNum(1,999) );
            $("#actor_down").val( rndNum(1,999) );
            $("#actor_score").val( rndNum(10) );
            $("#actor_score_all").val( rndNum(1000) );
            $("#actor_score_num").val( rndNum(100) );
        });

        var ue = editor_getEditor('actor_content');
    });

    function getExtend(id){
        $.post(EXTEND_URL, {id:id}, function(res) {

            if (res.code == 1) {
                $.each(res.data, function(key, value){
                    $('.actor_'+key+"_label").html('');
                    if(value != ''){
                        $.each(value, function(key2, value2){
                            $(".actor_"+key+"_label").append('<a class="layui-btn layui-btn-xs extend" href="javascript:;" data-id="actor_'+esc(key)+'">'+esc(value2)+'</a>');
                        });
                    }
                });
            }
        });
    }

    if (TYPE_ID !== '') {
        setTimeout(function () {
            getExtend(TYPE_ID)
        },1000);
    }
})();
