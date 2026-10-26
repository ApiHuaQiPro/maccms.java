@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <div class="layui-input-inline w150">
                    <select name="status">
                        <option value="">${page.lang().get("select_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("reviewed_not")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("reviewed")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="group">
                        <option value="">${page.lang().get("select_group")}</option>
                        @for(AdminRow vo : page.groupRows())
                        <option value="${vo.s("group_id")}" selected="${page.pEq("group", vo.s("group_id"))}">${page.text(vo.s("group_name"))}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

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
                <th width="100">${page.lang().get("group")}</th>
                <th width="80">${page.lang().get("status")}</th>
                <th width="80">${page.lang().get("points")}</th>
                <th width="130">${page.lang().get("last_login_time")}</th>
                <th width="130">${page.lang().get("last_login_ip")}</th>
                <th width="80">${page.lang().get("login_num")}</th>
                <th width="220">${page.lang().get("related_data")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("user_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("user_id")}</td>
                <td>${vo.s("user_name")}</td>
                <td>${page.groupName(vo.n("group_id"))}</td>
                <td>
                    <input type="checkbox" name="status" checked="${vo.n("user_status") == 1}" value="${vo.s("user_status")}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("open")}|${page.lang().get("close")}" data-href="${page.url().build("field?col=user_status&ids=" + vo.s("user_id"))}">
                </td>
                <td>${vo.s("user_points")}</td>
                <td>@if(page.isToday(vo.n("user_login_time")))<font color="#FF0000">${page.day(vo.n("user_login_time"))}</font>@else${page.day(vo.n("user_login_time"))}@endif</td>
                <td>${page.ip(vo.n("user_login_ip"))}</td>
                <td>${vo.s("user_login_num")}</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("comment/data?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/comment_record")}">${page.lang().get("admin/user/comment_record")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("order/index?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/order_record")}">${page.lang().get("admin/user/order_record")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("ulog/index?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/visit_record")}">${page.lang().get("admin/user/visit_record")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("plog/index?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/point_record")}">${page.lang().get("admin/user/point_record")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("cash/index?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/withdrawals_record")}">${page.lang().get("admin/user/withdrawals_record")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("user/reward?uid=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("admin/user/three_distribution")}">${page.lang().get("admin/user/three_distribution")}</a>
                </td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("user_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
        <div id="pages" class="center"></div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/admin-list.js" data-cur-url="${page.curUrl()}" data-count="${page.list().total()}" data-limit="${page.list().limit()}" data-curr="${page.list().page()}"></script>
`)
