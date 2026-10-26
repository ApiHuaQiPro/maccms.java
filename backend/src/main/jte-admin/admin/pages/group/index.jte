@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.admin.web.view.AdminText
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
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
                <th width="100">${page.lang().get("admin/group/pack_day")}</th>
                <th width="100">${page.lang().get("admin/group/pack_week")}</th>
                <th width="100">${page.lang().get("admin/group/pack_month")}</th>
                <th width="100">${page.lang().get("admin/group/pack_year")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td>
                    @if(vo.n("group_id") > 2)
                    <input type="checkbox" name="ids[]" value="${vo.s("group_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary">
                    @endif
                </td>
                <td>${vo.s("group_id")}</td>
                <td>${vo.s("group_name")}</td>
                <td>
                    @if(vo.n("group_id") > 2)
                    <input type="checkbox" name="status" checked="${vo.n("group_status") == 1}" value="${vo.s("group_status")}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("open")}|${page.lang().get("close")}" data-href="${page.url().build("field?col=group_status&ids=" + vo.s("group_id"))}">
                    @endif
                </td>
                <td>${vo.s("group_points_day")}</td>
                <td>${vo.s("group_points_week")}</td>
                <td>${vo.s("group_points_month")}</td>
                <td>${vo.s("group_points_year")}</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("group_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    @if(vo.n("group_id") > 2)
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("group_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                    @endif
                </td>
            </tr>
            @endfor
            </tbody>
        </table>

    </form>

    <blockquote class="layui-elem-quote layui-quote-nm">
        ${AdminText.trusted(page.lang().get("admin/group/help_tip"))}
    </blockquote>
</div>
`)
