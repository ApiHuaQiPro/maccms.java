@import com.maccms.backend.admin.web.view.collect.CollectListView
@import com.maccms.backend.collect.application.ProvideKind
@param CollectListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">
        <div class="layui-btn-group">
            @for(ProvideKind k : page.breakpoints())
            <a href="${page.breakHref(k)}" class="layui-btn layui-btn-danger ">${page.breakLabel(k)}</a>
            @endfor
        </div>
    </div>
    <hr>


</div>
`)
