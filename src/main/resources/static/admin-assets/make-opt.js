/*
 * 生成静态管理页的脚本（B16，docs/architecture/25-b16-static-timming.md 第 8 节）。取自旧 application/admin/view/make/opt.html 末尾的内联脚本：
 * 旧按钮的 onclick="post('…')" 改为 data-make 属性，curUrl 改由本 script 标签的 data-cur-url 提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var curUrl = self.getAttribute('data-cur-url');
    layui.use(['form', 'layer'], function () {
        var form = layui.form
                , layer = layui.layer;


    });
    function post(p)
    {
        $("#form1").attr("action", curUrl + "?"+p);
        $("#form1").submit();
    }
    $(document).on('click', '[data-make]', function () {
        post($(this).attr('data-make'));
    });
})();
