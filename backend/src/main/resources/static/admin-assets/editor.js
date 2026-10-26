/*
 * 富文本编辑器的全局函数（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/public/editor.html → extend/editor/ueditor.html 的两段内联脚本：上传地址（旧
 * {:url('upload/upload')}?from=ueditor&flag=<控制器>_editor&input=upfile）与编辑器名改由本 script 标签的 data-* 属性提供。
 * 旧 editor.html 在 static/editor/<编辑器> 不存在而 static/ueditor 存在时一律用 ueditor（仓库只有 ueditor），新版同样只提供 ueditor。
 */
(function () {
    var self = document.currentScript;
    window.UEDITOR_CONFIG.serverUrl = self.getAttribute('data-server-url');
    window.EDITOR = UE;
    window.editor = self.getAttribute('data-editor');
    window.editor_getEditor = function (obj)
    {
        return EDITOR.getEditor(obj);
    };
    window.editor_setContent = function (obj,html)
    {
        return obj.setContent(html);
    };
    window.editor_getContent = function (obj)
    {
        return obj.getContent();
    };
})();
