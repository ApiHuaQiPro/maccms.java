@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >
        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("index")}">
                <div class="layui-input-inline w150">
                    <select name="type">
                        <option value="">${page.lang().get("select_genre")}</option>
                        <option value="1" selected="${page.pEq("type", "1")}">${page.lang().get("admin/plog/points_recharge")}</option>
                        <option value="2" selected="${page.pEq("type", "2")}">${page.lang().get("admin/plog/reg_promote")}</option>
                        <option value="3" selected="${page.pEq("type", "3")}">${page.lang().get("admin/plog/visit_promote")}</option>
                        <option value="4" selected="${page.pEq("type", "4")}">${page.lang().get("one_level_distribution")}</option>
                        <option value="5" selected="${page.pEq("type", "5")}">${page.lang().get("two_level_distribution")}</option>
                        <option value="6" selected="${page.pEq("type", "6")}">${page.lang().get("three_level_distribution")}</option>
                        <option value="7" selected="${page.pEq("type", "7")}">${page.lang().get("admin/plog/points_upgrade")}</option>
                        <option value="8" selected="${page.pEq("type", "8")}">${page.lang().get("admin/plog/points_buy")}</option>
                        <option value="9" selected="${page.pEq("type", "9")}">${page.lang().get("admin/plog/points_withdrawal")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("del") + "?ids=1&all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>
        </div>
    </div>

     <form class="layui-form " method="post" id="pageListForm">
         <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="80">${page.lang().get("id")}</th>
                <th width="100">${page.lang().get("user")}</th>
                <th width="50">${page.lang().get("genre")}</th>
                <th width="50">${page.lang().get("points")}</th>
                <th width="200">${page.lang().get("remarks")}</th>
                <th width="140">${page.lang().get("admin/plog/log_time")}</th>
                <th width="50">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("plog_id")}</td>
                <td>[${vo.s("user_id")}]${page.text(vo.s("user_name"))}</td>
                <td>${page.plogType(vo.n("plog_type"))}</td>
                <td>@if(page.income(vo.n("plog_type")))+@else-@endif${vo.s("plog_points")}</td>
                <td>${page.text(vo.s("plog_remarks"))}</td>
                <td>@if(page.isToday(vo.n("plog_time")))<font color="#FF0000">${page.day(vo.n("plog_time"))}</font>@else${page.day(vo.n("plog_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("plog_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
