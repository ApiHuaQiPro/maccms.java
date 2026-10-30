@import com.maccms.backend.admin.web.view.content.AnnexFileView
@import com.maccms.backend.content.application.view.AnnexDirectory
@param AnnexFileView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-btn-box lh30" >
        <div class="layui-btn-group fl">
            <a href="${page.url().build("data")}" class="layui-btn layui-btn-primary "><i class="layui-icon">&#xe620;</i>${page.lang().get("return")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
        </div>
        <div class="page-filter fr" >

        </div>
    </div>

    <form class="layui-form " method="post" id="pageListForm">
    <table class="layui-table mt10">
        <thead>
        <tr>
            <th><input type="checkbox" lay-skin="primary" lay-filter="allChoose">${page.lang().get("file_name")}</th>
            <th width="200">${page.lang().get("file_size")}</th>
            <th width="200">${page.lang().get("file_time")}</th>
        </tr>
        </thead>
        <tbody>
        @if(page.dir().child())
        <tr><td colspan="3"><a href="${page.dirUrl(page.dir().uppath())}">...${page.lang().get("return_parent_dir")}</a></td></tr>
        @endif

            @for(AnnexDirectory.Item vo : page.dir().items())
            <tr>
                @if(vo.file())
                <th><input type="checkbox" name="ids[]" value=".${page.attr(vo.path())}" class="layui-checkbox checkbox-ids" lay-skin="primary"> <a href="${page.attr(vo.path())}" target="_blank">${page.text(vo.name())}</a></th>
                <td>${vo.size()}</td>
                <td>@if(page.isToday(vo.time()))<font color="#FF0000">${page.day(vo.time())}</font>@else${page.day(vo.time())}@endif</td>
                @else
                <th><a href="${page.dirUrl(vo.path())}">${page.text(vo.name())}</a></th>
                <td></td>
                <td></td>
                @endif
            </tr>
            @endfor
        </tbody>
        <tfoot>
            <tr><td colspan="3">${page.lang().get("sum")}<b class="red">${page.dir().dirs()}</b>${page.lang().get("dir")},<b class="red">${page.dir().files()}</b>${page.lang().get("file")},${page.lang().get("occupies")}<b class="red">${page.dir().size()}</b>${page.lang().get("space")}</td></tr>
        </tfoot>
    </table>
    </form>
</div>
`)
