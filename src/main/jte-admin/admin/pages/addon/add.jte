@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="layui-tab layui-tab-brief" lay-filter="tabs">
        <ul class="layui-tab-title">
            <li class="btn-local" ><a href="${page.url("index")}">${page.lang().get("local_app")}</a></li>
            <li class="layui-this"><a href="${page.url("add")}">${page.lang().get("local_setup")}</a></li>
        </ul>
        <div class="layui-tab-content">
            <blockquote class="layui-elem-quote layui-quote-nm">
                ${page.lang().get("admin/addon/add_tip")}
            </blockquote>
            <input type="hidden" id="token" name="__token__" value="${page.token()}" />
            <button type="button" class="layui-btn layui-upload" id="upload1">${page.lang().get("upload")}</button>
        </div>
    </div>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/addon-add.js" data-local-url="${page.url("addon/local")}" data-msg-uploading="${page.lang().get("upload_ing")}"></script>
`)
