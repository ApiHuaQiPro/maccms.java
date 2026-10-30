@import com.maccms.backend.admin.web.view.collect.CjListView
@import com.maccms.backend.content.application.view.AdminRow
@param CjListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >
        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" data-full="1" class="layui-btn layui-btn-primary j-iframe" data-width="600px" data-height="400px"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("import")}" class="layui-btn layui-btn-primary layui-upload" ><i class="layui-icon">&#xe654;</i>${page.lang().get("import")}</a>
        </div>
    </div>

    <form method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="50">${page.lang().get("id")}</th>
                <th >${page.lang().get("name")}</th>
                <th width="120">${page.lang().get("cj_time")}</th>
                <th width="250">${page.lang().get("opt_content")}</th>
                <th width="250">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.list().rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("nodeid")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("nodeid")}</td>
                <td>${page.text(vo.s("name"))}</td>
                <td>${page.lastdate(vo)}</td>
                <td>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-iframe" data-href="${page.url().build("col_url") + "?id=" + vo.s("nodeid")}">${page.lang().get("admin/cj/cj_url")}</a>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-iframe" data-href="${page.url().build("col_content") + "?id=" + vo.s("nodeid")}">${page.lang().get("admin/cj/cj_content")}</a>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-iframe" data-href="${page.url().build("publish") + "?id=" + vo.s("nodeid") + "&status=2"}">${page.lang().get("admin/cj/content_publish")}</a>
                </td>
                <td>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-iframe" data-full="1" data-href="${page.url().build("info") + "?id=" + vo.s("nodeid")}" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-iframe" data-href="${page.url().build("program") + "?id=" + vo.s("nodeid")}" title="${page.lang().get("admin/cj/publish_plan")}">${page.lang().get("admin/cj/publish_plan")}</a>
                    <a class="layui-btn layui-btn-primary layui-btn-xs" href="${page.url().build("export") + "?id=" + vo.s("nodeid")}" title="${page.lang().get("export")}">${page.lang().get("export")}</a>
                    <a class="layui-btn layui-btn-primary layui-btn-xs j-tr-del" href="${page.url().build("del") + "?ids=" + vo.s("nodeid")}" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
        <div id="pages" class="center"></div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/cj-index.js" data-import-url="${page.url().build("cj/import")}" data-msg-uploading="${page.lang().get("upload_ing")}"></script>
`)
