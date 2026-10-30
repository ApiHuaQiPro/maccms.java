/*
 * 视频、文章、网址批量处理页的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/{vod,art,website}/batch.html 末尾的内联脚本，逻辑不变。
 */
(function () {
    layui.use(['form'], function () {

    });

    $('.btn_submit').click(function(){
        $('form').submit();
    })
})();
