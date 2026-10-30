@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/database/export.html（表列表）。"优化""修复"按钮照旧保留，新版服务端拒绝执行（24 文档 D-B15-1）。
     大小列旧版是 {$vo['Data_length']/1024|round=###,2}，由视图模型算好放在 size_kb、free_kb。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-toolbar-box" >
        <ul class="layui-tab-title mb10">
            <li class="layui-this"><a href="${page.url("index")}">${page.lang().get("admin/database/backup_db")}</a></li>
            <li><a href="${page.url("index")}?group=import">${page.lang().get("admin/database/import_db")}</a></li>
        </ul>
        <div class="layui-btn-group">
            <a data-href="${page.url("export")}" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe62d;</i>${page.lang().get("admin/database/backup_db")}</a>
            <a data-href="${page.url("optimize")}" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe631;</i>${page.lang().get("admin/database/optimize_db")}</a>
            <a data-href="${page.url("repair")}" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe60c;</i>${page.lang().get("admin/database/repair_db")}</a>
        </div>
    </div>
    <form id="pageListForm" class="layui-form">
        <table class="layui-table mt10" lay-even="" lay-skin="row">
            <colgroup>
                <col width="50">
            </colgroup>
            <thead>
            <tr>
                <th><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th>${page.lang().get("admin/database/table")}</th>
                <th>${page.lang().get("admin/database/count")}</th>
                <th>${page.lang().get("admin/database/size")}</th>
                <th>${page.lang().get("admin/database/redundancy")}</th>
                <th>${page.lang().get("remarks")}</th>
                <th width="90">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(var vo : page.each(page.at("list")))
            <tr>
                <td><input type="checkbox" name="ids[]" class="layui-checkbox checkbox-ids" value="${page.v(page.at(vo.value(), "Name"))}" lay-skin="primary"></td>
                <td>${page.t(page.at(vo.value(), "Name"))}</td>
                <td>${page.t(page.at(vo.value(), "Rows"))}</td>
                <td>${page.t(page.at(vo.value(), "size_kb"))} kb</td>
                <td>${page.t(page.at(vo.value(), "free_kb"))} kb</td>
                <td>${page.t(page.at(vo.value(), "Comment"))}</td>
                <td>
                        <a data-href="${page.url("optimize?ids=" + page.s(page.at(vo.value(), "Name")))}" class="layui-badge-rim j-ajax">${page.lang().get("admin/database/optimize")}</a>
                        <a data-href="${page.url("repair?ids=" + page.s(page.at(vo.value(), "Name")))}" class="layui-badge-rim  j-ajax">${page.lang().get("admin/database/repair")}</a>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
    </form>
</div>
`)
