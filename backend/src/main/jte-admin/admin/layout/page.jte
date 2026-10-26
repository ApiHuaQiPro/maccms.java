@import com.maccms.backend.admin.web.view.AdminHead
@import com.maccms.backend.common.lang.LegacyLang
@import gg.jte.Content
@param AdminHead head
@param LegacyLang lang
@param Content styles = null
@param Content content
@param Content scripts = null
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <title>${head.title()} - ${lang.get("admin/public/head/title")}</title>
    <link rel="stylesheet" href="${head.staticPath()}/layui/css/layui.css">
    <link rel="stylesheet" href="${head.staticPath()}/css/admin_style.css?${head.version()}">
    <script type="text/javascript" src="${head.staticPath()}/js/jquery.js"></script>
    <script type="text/javascript" src="${head.staticPath()}/layui/layui.js"></script>
    <script type="text/javascript" src="${head.assetsPath()}/env.js" data-root-path="${head.sitePath()}" data-admin-path="${head.adminPath()}"></script>
    @if(styles != null)${styles}@endif
</head>
<body>
${content}
<script type="text/javascript" src="${head.staticPath()}/js/admin_common.js?${head.version()}"></script>
@if(scripts != null)${scripts}@endif
</body>
</html>
