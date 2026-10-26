/*
 * 分类表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/type/info.html:206-299 的内联脚本；上传地址、语言包文字与分类类型改由本 script 标签的 data-* 属性提供
 * （旧版 selectOnChange({$info.type_mid}) 在新增时输出 selectOnChange()，这里同样传 undefined）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_TPL = self.getAttribute('data-msg-tpl');
    var MID = self.getAttribute('data-mid');

    function selectOnChange(id)
    {
        var flag = id;
        var type_tpl = 'type.html';
        var type_tpl_list = 'show.html';
        var type_tpl_detail = 'detail.html';
        var type_tpl_play = 'play.html';
        var type_tpl_down = 'down.html';

        if(flag != 1){
            $(".vod-list").hide();
            type_tpl_play = '';
            type_tpl_down = '';
        }
        else{
            $(".vod-list").show();
        }
        if($('input[name="type_id"]').val() ==''){
            $('input[name="type_tpl"]').val(type_tpl);
            $('input[name="type_tpl_list"]').val(type_tpl_list);
            $('input[name="type_tpl_detail"]').val(type_tpl_detail);
            $('input[name="type_tpl_play"]').val(type_tpl_play);
            $('input[name="type_tpl_down"]').val(type_tpl_down);
        }
    }
    layui.use(['form','upload', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , upload = layui.upload
                , $ = layui.jquery;

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

        // 验证
        form.verify({
            type_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            type_tpl: function (value) {
                if (value == "") {
                    return MSG_TPL;
                }
            }
        });


        form.on('select(type_mid)', function(data){
            selectOnChange(data.value);
        });


        selectOnChange(MID === '' ? undefined : MID);
    });
})();
