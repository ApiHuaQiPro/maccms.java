@import com.maccms.backend.admin.web.view.LoginPage
@param LoginPage page
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="renderer" content="webkit">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <title>${page.lang().get("admin/index/login/title")}</title>
    <link rel="stylesheet" href="${page.head().staticPath()}/layui/css/layui.css?v=1024">
    <link rel="stylesheet" href="${page.head().staticPath()}/css/admin_style.css?v=1024">
    <style type="text/css">
        body {
            color:#999;
            background-size:cover;
        }
    </style>
</head>
<body class="login-body body">
<div class="login-head">
    <h1><a>${page.lang().get("admin/index/login/tip_welcome")}</a></h1>
</div>
<div class="login-box">
    <form class="layui-form layui-form-pane" method="post" action="">
        <div class="layui-form-item">
            <h3>${page.lang().get("admin/index/login/tip_sys")}</h3>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("account")}：</label>
            <div class="layui-input-block">
                <input type="text" name="admin_name" class="layui-input" lay-verify="admin_name" placeholder="" autocomplete="on" maxlength="20"/>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("pass")}：</label>
            <div class="layui-input-block">
                <input type="password" name="admin_pwd" class="layui-input" lay-verify="admin_pwd" placeholder="" maxlength="20"/>
            </div>
        </div>
        @if(page.verify())
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("verify")}：</label>
            <div class="layui-input-block">
                <input type="number" name="verify" class="layui-input" lay-verify="verify" placeholder="" maxlength="4"  max="9999"/><img id="verify_img" src="${page.verifyUrl()}" alt="">
            </div>
        </div>
        @endif
        <button type="button" class="layui-btn btn-submit" lay-submit="" lay-filter="sub">${page.lang().get("admin/index/login/btn_submit")}</button>
    </form>
    <div class="copyright">
        ${page.lang().get("maccms_copyright")}
    </div>

    <fieldset class="layui-elem-field">
        <legend>${page.lang().get("admin/index/login/tip_declare")}</legend>
        <div class="layui-field-box">
            ${page.lang().get("admin/index/login/tip_declare_txt")}
        </div>
    </fieldset>
</div>

<script type="text/javascript" src="${page.head().staticPath()}/layui/layui.js"></script>
<script type="text/javascript" src="${page.head().staticPath()}/js/admin_common.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/login.js" data-login-url="${page.loginUrl()}" data-index-url="${page.indexUrl()}" data-msg-no="${page.lang().get("admin/index/login/verify_no")}" data-msg-pass="${page.lang().get("admin/index/login/verify_pass")}" data-msg-verify="${page.lang().get("admin/index/login/verify_verify")}" data-msg-wait="${page.lang().get("wait_submit")}"></script>
</body>
</html>
