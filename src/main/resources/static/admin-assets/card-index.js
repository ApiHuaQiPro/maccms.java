/*
 * 卡密列表的导出按钮（B14，docs/architecture/23-b14-admin-member.md 第 8 节）。
 * 取自旧 application/admin/view/card/index.html:110-114 的内联脚本（分页部分与其他列表相同，在 admin-list.js）；导出地址改由本 script 标签的
 * data-* 属性提供。除此之外逻辑不变：把搜索表单序列化后加上 export=1，写进页面里隐藏的 iframe 下载 CSV。
 */
(function () {
    var self = document.currentScript;
    var EXPORT_URL = self.getAttribute('data-export-url');
    layui.use(['laypage', 'layer'], function() {
        $('#btnExport').click(function(){
            var par = $('#searchForm').serialize() + '&export=1';

            $('#if').attr('src', EXPORT_URL + "?" + par);
        });
    });
})();
