@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="actor_id" value="${page.v("actor_id")}">

        <div class="layui-tab">
            <ul class="layui-tab-title ">
                <li class="layui-this">${page.lang().get("base_info")}</li>

            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("file_name")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("annex_file")}" readonly="readonly">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("file_size")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("annex_size")}" readonly="readonly">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("type")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("annex_type")}" readonly="readonly">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("time")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.day("annex_time")}" readonly="readonly">
                        </div>
                    </div>

        </div>

            </div>
        </div>


    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/actor-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=actor"}" data-extend-url="${page.url().build("type/extend")}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-type-id="${page.editing("actor_id") ? page.info().s("type_id") : ""}"></script>
`)
