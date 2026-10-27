/*
 * 插件"本地安装"页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 11 节）。逐行取自旧 application/admin/view/addon/add.html:21-52 的
 * 内联脚本；上传地址与语言包文字改由本 script 标签的 data-* 属性提供。旧版的上传接口 addon/local 在校验表单令牌后直接输出 closed（不安装），
 * 新版照旧。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var LOCAL_URL = self.getAttribute('data-local-url');
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
            ,url: LOCAL_URL + "?__token__=" + $('#token').val()
            ,method: 'post'
            ,exts:'zip'
            ,before: function(input) {
                layer.msg(MSG_UPLOADING, {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                }

                setTimeout(function () {
                    layer.closeAll();
                    location.reload();
                },2000);
            }
        });

    });
})();
