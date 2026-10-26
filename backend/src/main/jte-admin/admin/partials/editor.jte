@import com.maccms.backend.admin.web.view.AdminHead
@param AdminHead head
@param String serverUrl
<script type="text/javascript" src="${head.staticPath()}/ueditor/ueditor.config.js"></script>
<script type="text/javascript" src="${head.staticPath()}/ueditor/ueditor.all.min.js"></script>
<script type="text/javascript" src="${head.assetsPath()}/editor.js" data-server-url="${serverUrl}" data-editor="ueditor"></script>
