@import com.maccms.backend.admin.web.view.content.UploadIndexView
@param UploadIndexView page
<body style="margin:0px;padding:0px;text-align:left">
<form name="form" enctype="multipart/form-data" action="${page.action()}" method="post">
    <input type="hidden" name="path" value="${page.path()}">
    <input type="hidden" name="id" value="${page.id()}">
    <input type="file" id="file1" name="file1" style="width: 200px; height: 25px;">
    <input type="submit" name="submit" class="input" value="${page.lang().get("upload")}">
</form>
</body>
