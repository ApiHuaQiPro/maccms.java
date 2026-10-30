/*
 * 资源库预览页的页面脚本（B18，docs/architecture/26-b18-collect-preview.md 第 6 节）。逐行取自旧 application/admin/view/collect/vod.html:76-126
 * 的内联脚本（art/actor/role/website.html 相同，只有绑定回调的写法不同）；地址、分页数字与语言包文字改由本 script 标签的 data-* 属性提供。
 * 绑定回调 onSubmitResult 按 data-bind-style 选旧模板各自的写法：视频与文章显示绑定的分类名，演员、角色、网址显示"取消绑定"
 * （旧 actor.html 未绑定一支的字符串少了一个引号，整段脚本报错；新版按其他模板的写法输出）。其他逻辑不变。
 */
var onSubmitResult;
(function () {
    var self = document.currentScript;
    var curUrl = self.getAttribute('data-cur-url');
    var count = Number(self.getAttribute('data-count'));
    var limit = Number(self.getAttribute('data-limit'));
    var curr = Number(self.getAttribute('data-curr'));
    var BIND_STYLE = self.getAttribute('data-bind-style');
    var MSG_BIND = self.getAttribute('data-msg-bind');
    var MSG_UNBIND = self.getAttribute('data-msg-unbind');
    layui.use(['laypage', 'layer','form'], function() {
        var laypage = layui.laypage
                , layer = layui.layer,
                form = layui.form;

        laypage.render({
            elem: 'pages'
            ,count: count
            ,limit: limit
            ,curr: curr
            ,layout: ['count', 'prev', 'page', 'next', 'limit', 'skip']
            ,jump: function(obj,first){
                if(!first){
                    location.href = curUrl.replace('%7Bpage%7D',obj.curr).replace('%7Blimit%7D',obj.limit);
                }
            }
        });

        $('#wd').on('keydown', function (event) {
            if (event.keyCode == 13) {
                $('.j-btn').click();
                return false;
            }
        });

        $('.j-btn').click(function(){
           var wd = $('input[name="wd"]').val();
            var url = changeParam(curUrl,'wd',wd);
            location.href = url.replace('%7Bpage%7D',1).replace('%7Blimit%7D','');
        });

        $('.select_type').click(function(){
            var t = $(this).attr('data-id');
            var url = changeParam(curUrl,'t',t);
            location.href = url.replace('%7Bpage%7D',1).replace('%7Blimit%7D','');
        });

    });
    onSubmitResult = function (res)
    {
        if(res.data.st==1){
            if (BIND_STYLE == 'name') {
                $('#'+res.data.id).html('<span class="red">['+ res.data.local_type_name +']</span>');
            } else {
                $('#'+res.data.id).html('<span class=red>[' + MSG_UNBIND + ']</span>');
            }
        }
        else{
            $('#'+res.data.id).html('[' + MSG_BIND + ']');
        }
    };
})();
