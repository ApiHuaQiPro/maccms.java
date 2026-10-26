/*
 * 视频列表页的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/vod/index.html 末尾内联脚本里分页之外的部分（分页由 admin-list.js 处理）：小屏幕下名称列加宽。逻辑不变。
 */
(function () {
    layui.use(['layer', 'form'], function () {
        var $ = layui.jquery;
        // 小屏幕下名称问题
        if ($('body').width() <= 600) {
            $('#table_th_vod_name').attr('width', '300');
        }
    });
})();
