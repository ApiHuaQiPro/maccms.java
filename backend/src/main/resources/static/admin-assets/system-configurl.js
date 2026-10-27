/*
 * URL 参数配置页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）：逐行取自旧 application/admin/view/system/configurl.html:395-423
 * 的内联脚本，逻辑不变（旧脚本里的 sethtmldir 没有任何调用处，照旧保留）。
 */
layui.use(['element', 'form', 'layer'], function() {
    var element = layui.element
        ,form = layui.form
        , layer = layui.layer;

    form.on('select(select_path)', function(data){
        $(this).parent().parent().parent().parent().find('input').val(data.value);
    });

    element.on('tab(tb1)', function(){
        $.cookie('configurl_tab', this.getAttribute('lay-id'));
    });

    if( $.cookie('configurl_tab') !=null ) {
        element.tabChange('tb1', $.cookie('configurl_tab'));
    }

});

function sethtmldir(id,value){
    alert(id + '----' + value);
    if(value){
        $("input[name='"+id+"']").val(value);
    }
}
