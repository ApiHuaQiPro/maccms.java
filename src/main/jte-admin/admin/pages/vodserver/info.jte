@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("status")}：</label>
            <div class="layui-input-block">
                <input name="status" type="radio" id="rad-1" value="0" title="${page.lang().get("disable")}" checked="${!page.eq(page.at("info.status"), 1L)}">
                <input name="status" type="radio" id="rad-2" value="1" title="${page.lang().get("enable")}" checked="${page.eq(page.at("info.status"), 1L)}">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("code")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v(page.at("info.from"))}" placeholder="${page.lang().get("admin/vodplayer/code_tip")}" id="from" name="from"  readonly="${!page.eq(page.at("info.from"), "")}">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("name")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v(page.at("info.show"))}" placeholder="" id="show" name="show">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/vodserver/url")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v(page.at("info.des"))}" placeholder="" id="des" name="des">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("sort")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v(page.at("info.sort"))}" placeholder="${page.lang().get("admin/vodplayer/sort_tip")}" id="sort" name="sort">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("tip")}：</label>
            <div class="layui-input-block">
                <textarea name="tip" cols="" rows="" class="layui-textarea"  placeholder="" >${page.t(page.at("info.tip"))}</textarea>
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
<script type="text/javascript" src="${page.head().assetsPath()}/player-info.js" data-msg-code-empty="${page.lang().get("admin/vodplayer/code_empty")}" data-msg-name-empty="${page.lang().get("name_empty")}"></script>
`)
