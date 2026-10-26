/*
 * URL 推送管理页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧 application/admin/view/urlsend/index.html:107-140
 * 的内联脚本；推送地址改由本 script 标签的 data-push-url 提供，各推送按钮的 onclick="post('…');" 改为在这里按 data-post 绑定
 * （内容安全策略不允许内联事件）。除此之外逻辑不变。推送本身（E09）在 B28 实现，此前服务端返回"由 B28 批次提供"的提示。
 */
(function () {
    var self = document.currentScript;
    var curUrl = self.getAttribute('data-push-url');
    layui.use(['element', 'form', 'layer'], function() {
        var element = layui.element
            ,form = layui.form
            , layer = layui.layer;


        element.on('tab(tb1)', function(){
            $.cookie('urlsend_tab', this.getAttribute('lay-id'));
        });

        if( $.cookie('urlsend_tab') !=null ) {
            element.tabChange('tb1', $.cookie('urlsend_tab'));
        }



    });
    function post(p)
    {
        var limit = $('#limit').val();
        var page = $('#page').val();
        var ac = $('#ac').val();
        var range = $('input[name="range"]:checked').val();

        $("#form_post").attr("action", curUrl + "?"+ 'ac=' +ac +'&limit='+limit +'&page='+page + '&range=' + range +'&' + p );
        $("#form_post").submit();
    }

    $(document).on('click', '[data-post]', function () {
        post(this.getAttribute('data-post'));
    });

    $(function () {
        if( $.cookie('urlsend_tab') == undefined) {
            $('.layui-tab-title').find('li').first().addClass('layui-this');
            $('.layui-tab-item').first().addClass('layui-show');
        }
    });
})();
