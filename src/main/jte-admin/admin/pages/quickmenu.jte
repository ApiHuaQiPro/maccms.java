@import com.maccms.backend.admin.web.view.AdminText
@import com.maccms.backend.admin.web.view.QuickmenuPage
@param QuickmenuPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <fieldset class="layui-elem-field layui-field-title" class="mt10">
        <legend>${page.lang().get("admin/index/quickmenu/name")}</legend>
    </fieldset>

    <blockquote class="layui-elem-quote layui-quote-nm">
        ${AdminText.trusted(page.lang().get("admin/index/quickmenu/tip"))}
    </blockquote>

    <form class="layui-form" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-form-item layui-form-text">
            <textarea name="quickmenu" placeholder="${page.lang().get("filed_empty")}" class="layui-textarea" style="height:600px; resize:none">${page.quickmenu()}</textarea>
        </div>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>
`)
