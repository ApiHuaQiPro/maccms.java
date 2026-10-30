@import com.maccms.backend.admin.web.view.AdminListView
@param AdminListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="layui-btn-group">
            <a data-href="${page.infoUrl()}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.delUrl()}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="100">${page.lang().get("id")}</th>
                <th >${page.lang().get("name")}</th>
                <th width="100">${page.lang().get("status")}</th>
                <th width="140">${page.lang().get("last_login_time")}</th>
                <th width="140">${page.lang().get("last_login_ip")}</th>
                <th width="130">${page.lang().get("login_num")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminListView.Item vo : page.rows())
            <tr>
                <td>
                    @if(!vo.self())
                    <input type="checkbox" name="ids[]" value="${vo.id()}" class="layui-checkbox checkbox-ids" lay-skin="primary">
                    @endif
                </td>
                <td>${vo.id()}</td>
                <td>${vo.name()}</td>
                <td>
                    <input type="checkbox" name="status" checked="${vo.status() == 1}" value="${vo.status()}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("enable")}|${page.lang().get("disable")}" data-href="${vo.fieldUrl()}">
                </td>
                <td>@if(vo.today())<font color="#FF0000">${vo.lastLoginTime()}</font>@else${vo.lastLoginTime()}@endif</td>
                <td>${vo.lastLoginIp()}</td>
                <td>${vo.loginNum()}</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${vo.infoUrl()}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    @if(!vo.self())
                    <a class="layui-badge-rim j-tr-del" data-href="${vo.delUrl()}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                    @endif
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
        <div id="pages" class="center"></div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/admin-list.js" data-cur-url="${page.curUrl()}" data-count="${page.total()}" data-limit="${page.limit()}" data-curr="${page.page()}"></script>
`)
