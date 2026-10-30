/*
 * 视频分集剧情页的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/vod/iplot.html 的内联脚本：每段剧情后的内联脚本 ueArray[序号] = editor_getEditor('vod_plot_detail序号')
 * 改为这里按页面里已有的段依次初始化；剧情段数与语言包文字改由本 script 标签的 data-* 属性提供，拼进 HTML 的文字先转义。旧脚本里绑定在
 * 页面上并不存在的"校正/倒序/去前缀"按钮上的处理（引用了本页未定义的函数）不再搬过来。其余逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var L = JSON.parse(self.getAttribute('data-lang'));
    var ueArray = [];
    var plot_arr_len = Number(self.getAttribute('data-plots-len'));

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    $('#plot_list > .layui-form-item[data-i]').each(function () {
        var i = $(this).attr('data-i');
        ueArray[i] = editor_getEditor('vod_plot_detail' + i);
    });

    layui.use(['form','upload', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                ;

        // 验证
        form.verify({
            vod_name: function (value) {
                if (value == "") {
                    return L['name_empty'];
                }
            }
        });


        $('.contents').on('click','.j-editor-clear',function(){
            $(this).parent().parent().find('textarea').val('');
        });
        $('.contents').on('click','.j-editor-remove',function(){
            $(this).parent().parent().remove();
        });
        $('.contents').on('click','.j-editor-up',function(){
            var current = $(this).parent().parent();
            var current_index = current.index();
            var prev = current.prev();
            if(current_index>0){
                current.insertBefore(prev);
            }
        });
        $('.contents').on('click','.j-editor-down',function(){
            var current = $(this).parent().parent();
            var next = current.next();

            if(next.length>0){
                current.insertAfter(next);
            }
        });


        $('.j-plot-add').on('click',function(){
            plot_arr_len++;
            var tpl='<div class="layui-form-item" data-i="'+plot_arr_len+'"><label class="layui-form-label">'+esc(L['admin/vod/plot'])+(plot_arr_len)+'：</label><div class="layui-input-inline w500"><input type="text" name="vod_plot_name[]" class="layui-input" placeholder="'+esc(L['admin/vod/plot_name'])+'"></div><div class="layui-input-inline w400 p10"><a href="javascript:void(0)" class="j-editor-clear">'+esc(L['clear'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">'+esc(L['del'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">'+esc(L['admin/vod/move_up'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">'+esc(L['admin/vod/move_down'])+'</a>&nbsp;</div><div class="p10 m20"></div><div class="layui-input-block"><textarea id="vod_plot_detail'+(plot_arr_len)+'" name="vod_plot_detail[]" class="layui-textarea" style="width:99%;height:250px"></textarea></div></div>';
            $("#plot_list").append(tpl);
            ueArray[plot_arr_len] = editor_getEditor( 'vod_plot_detail'+plot_arr_len );
        });

        if(plot_arr_len==0 ) {
            $('.j-plot-add').click();
        }
    });
})();
