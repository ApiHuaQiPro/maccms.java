/*
 * 自定义规则列表的页面脚本（B17，docs/architecture/25-b17-collect-config.md 第 7 节）。逐行取自旧 application/admin/view/cj/index.html:51-76
 * 的内联脚本；导入地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变（旧版导入不带表单令牌，照旧）。
 */
(function () {
    var self = document.currentScript;
    var IMPORT_URL = self.getAttribute('data-import-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    layui.use(['form','laypage', 'layer','upload'], function() {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                , upload = layui.upload;

        upload.render({
            elem: '.layui-upload'
            ,url: IMPORT_URL
            ,method: 'post'
            ,exts:'txt'
            ,before: function(input) {
                layer.msg(MSG_UPLOADING, {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                    return false;
                }
                location.reload();
            }
        });

    });
})();
