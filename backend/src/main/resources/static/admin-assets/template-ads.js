/*
 * 广告位列表（B15）：逐行取自旧 application/admin/view/template/ads.html 的内联脚本（复制调用代码）；未被调用的 data_info/data_del 不再保留。
 */
(function () {
    var clipboard = new ClipboardJS('.j-clipboard');
    clipboard.on('success', function(e) {
       layer.msg('copy ok');
    });
})();
