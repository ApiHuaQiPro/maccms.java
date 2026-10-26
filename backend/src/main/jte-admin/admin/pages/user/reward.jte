@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.admin.web.view.member.RewardView
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@param RewardView view
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="center mb10">
            <form class="layui-form " method="post">
                <div class="layui-input-inline w150">
                    <select name="level">
                        <option value="">${page.lang().get("admin/user/reward/select_level")}</option>
                        <option value="1" selected="${page.pEq("level", "1")}">${page.lang().get("admin/user/reward/one_distribution")}</option>
                        <option value="2" selected="${page.pEq("level", "2")}">${page.lang().get("admin/user/reward/two_distribution")}</option>
                        <option value="3" selected="${page.pEq("level", "3")}">${page.lang().get("admin/user/reward/three_distribution")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <input type="hidden" name="uid" value="${view.stats().uid()}">
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a class="layui-btn ">${page.lang().get("admin/user/reward/one_people_num")}【${view.stats().level1()}】${page.lang().get("admin/user/reward/total_commission_points")}【${view.stats().points1()}】</a>
            <a class="layui-btn layui-btn-normal">${page.lang().get("admin/user/reward/two_people_num")}【${view.stats().level2()}】${page.lang().get("admin/user/reward/total_commission_points")}【${view.stats().points2()}】</a>
            <a class="layui-btn layui-btn-warm">${page.lang().get("admin/user/reward/three_people_num")}【${view.stats().level3()}】${page.lang().get("admin/user/reward/total_commission_points")}【${view.stats().points3()}】</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="100">${page.lang().get("id")}</th>
                <th>${page.lang().get("name")}</th>
                <th width="120">${page.lang().get("group")}</th>
                <th width="120">${page.lang().get("status")}</th>
                <th width="120">${page.lang().get("admin/user/reward/distribution_level")}</th>
                <th width="130">${page.lang().get("reg_time")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("user_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("user_id")}</td>
                <td>${page.text(vo.s("user_name"))}</td>
                <td>${page.text(page.groupName(vo.n("group_id")))}</td>
                <td>@if(vo.n("user_status") == 1)<span class="layui-badge layui-bg-green">${page.lang().get("open")}</span>@else<span class="layui-badge">${page.lang().get("close")}</span>@endif</td>
                <td>@if(view.level(vo) == 1)${page.lang().get("admin/user/reward/one_distribution")}@elseif(view.level(vo) == 2)${page.lang().get("admin/user/reward/two_distribution")}@else${page.lang().get("admin/user/reward/three_distribution")}@endif</td>
                <td>@if(page.isToday(vo.n("user_reg_time")))<font color="#FF0000">${page.day(vo.n("user_reg_time"))}</font>@else${page.day(vo.n("user_reg_time"))}@endif</td>
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
