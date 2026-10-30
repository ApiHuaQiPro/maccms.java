@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/template/wizard.html 按标签类别（link/type/vod/…）生成 MacCMS 标签。jte 模板没有按条件查库的标签，
     数据都来自页面的视图模型，所以新版按内置的页面模板列出参数与可直接粘贴的 jte 写法（D-B15-15，24 文档第 7.5 节）。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form " action="">
        <blockquote class="layui-elem-quote layui-quote-nm">
            提示信息：新版前台模板是 jte，页面数据由各页面的视图模型提供（旧版的 {maccms:vod} 等标签不再适用）。下面按页面模板列出它的参数，
            以及按视图模型字段生成的输出、循环与判断写法，可复制到对应模板中使用。
        </blockquote>
        @for(var vo : page.each(page.at("list")))
        <div class="layui-form-item">
            <label class="layui-form-label">${page.s(page.at(vo.value(), "name")).replace("site/pages/", "").replace(".jte", "")}：</label>
            <div class="layui-input-block">
                <textarea class="layui-textarea" readonly="readonly" rows="8">${page.s(page.at(vo.value(), "snippet"))}</textarea>
            </div>
        </div>
        @endfor
    </form>
</div>
`)
