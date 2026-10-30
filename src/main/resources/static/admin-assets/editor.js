/*
 * 富文本编辑器的全局函数（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 取自旧 application/admin/view/public/editor.html → extend/editor/ueditor.html 的两段内联脚本：上传地址（旧
 * {:url('upload/upload')}?from=ueditor&flag=<控制器>_editor&input=upfile）与编辑器名改由本 script 标签的 data-* 属性提供。
 * 旧 editor.html 在 static/editor/<编辑器> 不存在而 static/ueditor 存在时一律用 ueditor（仓库只有 ueditor），新版同样只提供 ueditor。
 */
(function () {
    var self = document.currentScript;
    /*
     * 后台的内容安全策略不允许内联脚本（21 文档第 8.2 节）。ueditor 的 render 用 javascript: 地址的 iframe 写入编辑区文档，文档里的内联
     * 脚本再调用 editor._setup(document)；两者都被策略拦截，编辑区是空白页、编辑器永远不就绪（保存时取不到正文）。这里不改 ueditor 的文件：
     * render 创建这个 iframe 时去掉 javascript: 地址，取出它要写的同一份文档（去掉那段内联脚本），由本脚本写入 iframe 并调用 _setup，
     * 与原脚本做的事相同。
     */
    var render = UE.Editor.prototype.render;
    UE.Editor.prototype.render = function (container) {
        var me = this, domUtils = UE.dom.domUtils, create = domUtils.createElement, html = null;
        domUtils.createElement = function (doc, tag, attrs) {
            var src = attrs && attrs.src;
            if (tag === 'iframe' && typeof src === 'string' && src.indexOf('javascript:') === 0) {
                var from = src.indexOf('document.write("'), to = src.lastIndexOf('");document.close();');
                if (from > 0 && to > from) {
                    html = src.substring(from + 'document.write("'.length, to)
                        .replace(/<script[^>]*id='_initialScript'[^>]*>[\s\S]*?<\/script>/, '');
                    var copy = {};
                    for (var k in attrs) {
                        if (attrs.hasOwnProperty(k) && k !== 'src') {
                            copy[k] = attrs[k];
                        }
                    }
                    attrs = copy;
                }
            }
            return create.call(this, doc, tag, attrs);
        };
        try {
            render.call(this, container);
        } finally {
            domUtils.createElement = create;
        }
        var frame = html === null ? null : document.getElementById('ueditor_' + me.uid);
        if (frame) {
            var doc = frame.contentWindow.document;
            doc.open();
            doc.write(html);
            doc.close();
            setTimeout(function () {
                me._setup(doc);
            }, 0);
        }
    };
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
