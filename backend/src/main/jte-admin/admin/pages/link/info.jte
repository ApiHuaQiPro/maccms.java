@import com.maccms.backend.admin.web.view.content.LinkInfoView
@param LinkInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="link_id" name="link_id" type="hidden" value="${page.info().s("link_id")}">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("name")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.info().s("link_name")}" lay-verify="link_name" placeholder="" id="link_name" name="link_name">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("url")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.info().s("link_url")}" lay-verify="link_url" placeholder="" id="link_url" name="link_url">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("logo")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.info().s("link_logo")}" placeholder="" id="link_logo" name="link_logo">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("sort")}：</label>
            <div class="layui-input-inline w100">
                <input type="text" class="layui-input" value="${page.info().s("link_sort")}" placeholder="" id="link_sort" name="link_sort">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("genre")}：</label>
            <div class="layui-input-inline">
                <select class="w100" name="link_type">
                    <option value="0" selected >${page.lang().get("admin/link/text_link")}</option>
                    <option value="1" >${page.lang().get("admin/link/pic_link")}</option>
                </select>
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/link-info.js" data-msg-name="${page.lang().get("name_empty")}" data-msg-url="${page.lang().get("url_empty")}"></script>
`)
