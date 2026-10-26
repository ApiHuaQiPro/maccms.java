/*
 * 附件参数配置页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）：逐行取自旧 application/admin/view/system/configupload.html:174-188
 * 的内联脚本，逻辑不变。
 */
layui.use(['form','layer'], function(){
    // 操作对象
    var form = layui.form
            , layer = layui.layer;

    form.on('select(upload[mode])', function(data){
        $('.upload_mode').hide();
        $('.mode_'+ data.value).show();
    });


});
