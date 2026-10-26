/*
 * 播放器导入页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧 application/admin/view/vodplayer/import.html:24-53 的
 * 内联脚本；上传地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变（表单令牌照旧从页面里的 #token 取，放在查询串里）。
 */
(function () {
    var self = document.currentScript;
    var IMPORT_URL = self.getAttribute('data-import-url');
    var MSG_UPLOADING = self.getAttribute('data-msg-uploading');
    var url='';
    layui.use(['form','laypage', 'layer','upload','element'], function() {
        // 操作对象
        var form = layui.form
            , layer = layui.layer
            , upload = layui.upload
            ,element = layui.element;

        upload.render({
            elem: '.layui-upload'
            ,url: IMPORT_URL + "?__token__=" + $('#token').val()
            ,method: 'post'
            ,exts:'txt'
            ,before: function(input) {
                layer.msg(MSG_UPLOADING, {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                layer.msg(res.msg);
                setTimeout(function () {
                    layer.closeAll();
                    location.reload();
                },2000);
            }
        });

    });
})();
