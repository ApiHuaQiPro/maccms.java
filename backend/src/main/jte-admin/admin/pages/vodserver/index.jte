@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-toolbar-box">

        <div class="layui-btn-group">
            <a data-href="${page.url("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="80">${page.lang().get("sort")}</th>
                <th width="80">${page.lang().get("code")}</th>
                <th width="80">${page.lang().get("status")}</th>
                <th width="150">${page.lang().get("name")}</th>
                <th>${page.lang().get("remarks")}</th>
                <th>${page.lang().get("tip")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>

            @for(var item_vo : page.each(page.at("list")))
            <tr>
                <td>${page.t(page.at(item_vo.value(), "sort"))}</td>
                <td>${page.t(page.at(item_vo.value(), "from"))}</td>
                <td>@if(page.eq(page.at(item_vo.value(), "status"), 1L))<span class="layui-badge layui-bg-green">${page.lang().get("enable")}</span>@else<span class="layui-badge">${page.lang().get("disable")}</span>@endif </td>
                <td>${page.t(page.at(item_vo.value(), "show"))}</td>
                <td>${page.t(page.at(item_vo.value(), "des"))}</td>
                <td>${page.t(page.at(item_vo.value(), "tip"))}</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url("info?id=" + page.s(page.at(item_vo.value(), "from")))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url("del?ids=" + page.s(page.at(item_vo.value(), "from")))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
        </table>

    </form>
</div>
`)
