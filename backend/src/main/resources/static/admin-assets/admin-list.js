/*
 * 管理员列表页的页面脚本（B12，docs/architecture/21-b12-admin-framework.md 第 8 节）。
 * 取自旧 application/admin/view/admin/index.html:61-81 的内联脚本；地址与分页数字改由本 script 标签的 data-* 属性提供（旧版直接写进脚本，
 * limit 为非数字时页面脚本出错）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var curUrl = self.getAttribute('data-cur-url');
    var count = Number(self.getAttribute('data-count'));
    var limit = Number(self.getAttribute('data-limit'));
    var curr = Number(self.getAttribute('data-curr'));
    layui.use(['laypage', 'layer'], function() {
        var laypage = layui.laypage
                , layer = layui.layer;

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
    });
})();
