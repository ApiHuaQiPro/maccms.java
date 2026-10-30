@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/database/import.html（备份列表）。"还原"按钮照旧保留，新版服务端拒绝执行（24 文档 D-B15-1）。
     {:date('Ymd-His', $vo['time'])}、{:round($vo['size']/1024, 2)}、{:date('Y-m-d H:i:s', $vo['time'])}、strtotime($key) 由视图模型算好
     （name、size_k、time_text、id）。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-toolbar-box" >
        <ul class="layui-tab-title mb10">
            <li ><a href="${page.url("index")}">${page.lang().get("admin/database/backup_db")}</a></li>
            <li class="layui-this"><a href="${page.url("index")}?group=import">${page.lang().get("admin/database/import_db")}</a></li>
        </ul>
    </div>
    <form id="pageListForm" class="layui-form">
        <table class="layui-table mt10" lay-even="" lay-skin="row">
            <thead>
            <tr>
                <th>${page.lang().get("admin/database/backup_name")}</th>
                <th>${page.lang().get("admin/database/backup_num")}</th>
                <th>${page.lang().get("admin/database/backup_zip")}</th>
                <th>${page.lang().get("admin/database/backup_size")}</th>
                <th>${page.lang().get("admin/database/backup_time")}</th>
                <th width="80">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(var vo : page.each(page.at("list")))
            <tr>
                <td>${page.t(page.at(vo.value(), "name"))}</td>
                <td>${page.t(page.at(vo.value(), "part"))}</td>
                <td>${page.t(page.at(vo.value(), "compress"))}</td>
                <td>${page.t(page.at(vo.value(), "size_k"))} K</td>
                <td>${page.t(page.at(vo.value(), "time_text"))}</td>
                <td>
                    <div class="layui-btn-group">
                        <a data-href="${page.url("import?id=" + page.s(page.at(vo.value(), "id")))}" class="layui-badge-rim layui-btn-small j-ajax" confirm="${page.lang().get("admin/database/import_confirm")}">${page.lang().get("admin/database/import")}</a>
                        <a data-href="${page.url("del?id=" + page.s(page.at(vo.value(), "id")))}" class="layui-badge-rim layui-btn-small j-tr-del">${page.lang().get("del")}</a>
                    </div>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
    </form>
</div>
`)
