@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/template/index.html。旧模板里未使用的 data_info/data_del 两个内联函数没有调用方，不再输出；
     多出的 </a>、</tbody> 去掉（jte 要求标签成对）；表格下方加一行"前台当前使用的模板"（24 文档第 7 节）。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-btn-box lh30" >
        <div class="layui-btn-group fl">
            <a data-full="1" data-href="${page.url("info")}?fpath=${page.s(page.at("curpath"))}&fname=" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
        </div>
        <div class="page-filter fr" >

        </div>
    </div>

    <form class="layui-form layui-form-pane" action="">
        <table class="layui-table mt10">
        <thead>
        <tr>
            <th>${page.lang().get("file_name")}</th>
            <th width="200">${page.lang().get("file_des")}</th>
            <th width="200">${page.lang().get("file_size")}</th>
            <th width="200">${page.lang().get("file_time")}</th>
            <th width="100">${page.lang().get("opt")}</th>
        </tr>
        </thead>
        <tbody>
        @if(page.eq(page.at("ischild"), "1"))
        <tr><td colspan="4"><a href="${page.url("index")}?path=${page.rawurlencode(page.at("uppath"))}">...${page.lang().get("return_parent_dir")}</a></td></tr>
        @endif

            @for(var vo : page.each(page.at("files")))
            <tr>
                @if(page.eq(page.at(vo.value(), "isfile"), "1"))
                <th>${page.t(page.at(vo.value(), "name"))}</th>
                <td>${page.t(page.at(vo.value(), "note"))}</td>
                <td>${page.t(page.at(vo.value(), "size"))}</td>
                <td>@if(page.eq(page.at(vo.value(), "today"), "1"))<font color="#FF0000">${page.t(page.at(vo.value(), "time_text"))}</font>@else${page.t(page.at(vo.value(), "time_text"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url("info")}?fpath=${page.s(page.at(vo.value(), "path"))}&fname=${page.s(page.at(vo.value(), "name"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url("del")}?fname=${page.s(page.at(vo.value(), "fullname"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
                @else
                <th><a href="${page.url("index")}?path=${page.rawurlencode(page.at(vo.value(), "path"))}">${page.t(page.at(vo.value(), "name"))}</a></th>
                <td>${page.t(page.at(vo.value(), "note"))}</td>
                <td></td>
                <td>@if(page.eq(page.at(vo.value(), "today"), "1"))<font color="#FF0000">${page.t(page.at(vo.value(), "time_text"))}</font>@else${page.t(page.at(vo.value(), "time_text"))}@endif</td>
                <td></td>
                @endif
            </tr>
            @endfor
        </tbody>
        <tfoot>
            <tr><td colspan="5">${page.lang().get("admin/template/current_dir")}：${page.s(page.at("curpath")).replace('@', '/')}，${page.lang().get("sum")}<b class="red">${page.s(page.at("num_path"))}</b>${page.lang().get("dir")},<b class="red">${page.s(page.at("num_file"))}</b>${page.lang().get("file")},${page.lang().get("occupies")}<b class="red">${page.s(page.at("sum_size"))}</b>${page.lang().get("space")}</td></tr>
            <tr><td colspan="5">${page.s(page.at("engine"))}</td></tr>
        </tfoot>
    </table>
    </form>
</div>
`)
