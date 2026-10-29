@import com.maccms.backend.common.lang.LegacyLang
@import gg.jte.Content
@param LegacyLang lang
@param String staticPath
@param Content styles = null
@param Content content
@param Content scripts = null
<!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="renderer" content="webkit">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <title>${lang.get("install/title")}</title>
        <link rel="stylesheet" href="${staticPath}/layui/css/layui.css">
        <link rel="stylesheet" href="${staticPath}/css/admin_style.css">
        <link rel="stylesheet" href="${staticPath}/css/install.css">
        <script type="text/javascript" src="${staticPath}/layui/layui.js"></script>
    </head>
<body>
<div class="header">
    <h1>${lang.get("install/header")}</h1>
</div>
@if(styles != null)${styles}@endif
${content}
<div class="copyright">
    © MacCMS All Rights Reserved.
</div>
@if(scripts != null)${scripts}@endif
</body>
</html>
