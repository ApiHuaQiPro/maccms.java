/*
 * 网址表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/website/info.html 末尾的内联脚本；上传、扩展字典与附件删除的地址、img_key/img_api、语言包文字与编辑时的
 * 分类编号改由本 script 标签的 data-* 属性提供（旧版写进脚本的全局变量 UPLOAD_IMG_KEY、UPLOAD_IMG_API 照旧设置，供 admin_common.js 的
 * mac_url_img 使用）。扩展项与截图地址在拼进 HTML 前转义（旧版原样拼接；显示不变）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var SHOT_URL = self.getAttribute('data-shot-url');
    var EXTEND_URL = self.getAttribute('data-extend-url');
    var ANNEX_DEL_URL = self.getAttribute('data-annex-del-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_DEL = self.getAttribute('data-msg-del');
    var TYPE_ID = self.getAttribute('data-type-id');
    window.UPLOAD_IMG_KEY = self.getAttribute('data-img-key');
    window.UPLOAD_IMG_API = self.getAttribute('data-img-api');

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
            website_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            }
        });

        $(document).on("click", ".extend", function(){
            var $id = $(this).attr('data-id');
            var $val;
            if($id == 'website_class'||$id == 'website_keywords'){
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

        //多图片上传
        upload.render({
            elem: '.screenshot'
            ,url: SHOT_URL
            ,multiple: true
            ,before: function(obj){
                obj.preview(function(index, file, result){

                });
            }
            ,done: function(res){
                var val = res.data.file;
                var input = $("#website_pic_screenshot")
                var content = input.val();
                if(content!=''){
                    content += '\r\n';
                }
                content += val;
                input.val(content);
                $('.screenshot_list').append('<div data-src="'+esc(val)+'"><a href="javascript:;" class="del_screenshot">'+esc(MSG_DEL)+'</a><img src="'+esc(mac_url_img(val))+'" alt="" class="layui-upload-img screenshot-img"></div>');
            }
        });
        //监听文本框
        $('#website_pic_screenshot').keyup(function(e){
            let html = ``;
            var textArr = $(this).val().split(/[(\r\n)\r\n]+/);
            textArr.forEach((item,index)=>{
                if(!item){
                    textArr.splice(index,1);
                }else{
                    if(item.indexOf('$')>-1){
                        item = item.substring(item.indexOf('$')+1);
                    }
                    html += `<div data-src="${esc(item)}"><a href="javascript:;" class="del_screenshot">${esc(MSG_DEL)}</a><img src="${esc(mac_url_img(item))}"" alt="" class="layui-upload-img screenshot-img"></div>`;
                }
            });
            $('.screenshot_list').html(html);
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
            $("#website_hits").val( rndNum(5000,9999) );
            $("#website_hits_month").val( rndNum(1000,4999) );
            $("#website_hits_week").val( rndNum(300,999) );
            $("#website_hits_day").val( rndNum(1,299) );
            $("#website_up").val( rndNum(1,999) );
            $("#website_down").val( rndNum(1,999) );
            $("#website_score").val( rndNum(10) );
            $("#website_score_all").val( rndNum(1000) );
            $("#website_score_num").val( rndNum(100) );
        });
        $(document).on('click', '.del_screenshot', function() {
            var src = $(this).parent().attr('data-src');
            var input = $("#website_pic_screenshot")
            var content = input.val();
            console.log(content);
            var snsArr = content.split(/[(\r\n)\r\n]+/);
            snsArr.forEach((item,index)=>{
                if(!item || item == src){
                    snsArr.splice(index,1);//删除
                }
            });
            $(this).parent().remove();
            input.val(snsArr.join('\r\n'));//重新赋值
            $.get(ANNEX_DEL_URL, {ids:src}, function(res){});
        });
        var ue = editor_getEditor('website_content');
    });

    function getExtend(id){
        $.post(EXTEND_URL, {id:id}, function(res) {

            if (res.code == 1) {
                $.each(res.data, function(key, value){
                    $('.website_'+key+"_label").html('');
                    if(value != ''){
                        $.each(value, function(key2, value2){
                            $(".website_"+key+"_label").append('<a class="layui-btn layui-btn-xs extend" href="javascript:;" data-id="website_'+esc(key)+'">'+esc(value2)+'</a>');
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
