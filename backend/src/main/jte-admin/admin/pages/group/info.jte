@import com.maccms.backend.admin.web.view.member.GroupInfoView
@import com.maccms.backend.admin.web.view.AdminText
@import com.maccms.backend.content.application.view.AdminRow
@param GroupInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="group_id" name="group_id" type="hidden" value="${page.v("group_id")}">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("name")}：</label>
            <div class="layui-input-block  ">
                <input type="text" class="layui-input" value="${page.v("group_name")}" placeholder="" lay-verify="group_name" name="group_name">
            </div>
        </div>

        @if(page.custom())
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/group/pack_day")}${page.lang().get("points")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("group_points_day")}" placeholder="" lay-verify="group_points_day" name="group_points_day">
            </div>
            <label class="layui-form-label">${page.lang().get("admin/group/pack_week")}${page.lang().get("points")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("group_points_week")}" placeholder="" lay-verify="group_points_week" name="group_points_week">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/group/pack_month")}${page.lang().get("points")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("group_points_month")}" placeholder="" lay-verify="group_points_month" name="group_points_month">
            </div>
            <label class="layui-form-label">${page.lang().get("admin/group/pack_year")}${page.lang().get("points")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("group_points_year")}" placeholder="" lay-verify="group_points_year" name="group_points_year">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("status")}：</label>
            <div class="layui-input-block">
                    <input name="group_status" type="radio" value="0" title="${page.lang().get("disable")}" checked="${!page.enabled()}">
                    <input name="group_status" type="radio" value="1" title="${page.lang().get("enable")}" checked="${page.enabled()}">
            </div>
        </div>
        @endif

        <div class="layui-form-item ">
            <label class="layui-form-label">${page.lang().get("admin/group/popedom")}：</label>
            <div class="layui-input-block">
                <blockquote class="layui-elem-quote layui-quote-nm">
                    ${AdminText.trusted(page.lang().get("admin/group/popedom_tip"))}
                </blockquote>

                <div class="role-list-form ">
                @for(GroupInfoView.TypeEntry e : page.entries())
                    <dl class="role-list-form-top permission-list">
                        <dt>
                            ${page.lang().get("type")}：<input type="checkbox" value="${e.row().s("type_id")}" name="group_type[]" data-id="${e.k1()}" lay-skin="primary" lay-filter="roleAuth1" title="${e.prefix()}${page.attr(e.row().s("type_name"))}" checked="${page.typeChecked(e.row())}">
                            ${page.lang().get("popedom")}：<input type="checkbox" name="group_popedom[${e.row().s("type_id")}][1]" value="1" lay-skin="primary" title="${page.lang().get("admin/group/popedom_list")}" checked="${page.bit(e.row(), 1)}">
                            <input type="checkbox" name="group_popedom[${e.row().s("type_id")}][2]" value="2" lay-skin="primary" title="${page.lang().get("admin/group/popedom_detail")}" checked="${page.bit(e.row(), 2)}">
                            @if(e.row().n("type_mid") == 1)
                            <input type="checkbox" name="group_popedom[${e.row().s("type_id")}][3]" value="3" lay-skin="primary" title="${page.lang().get("admin/group/popedom_play")}" checked="${page.bit(e.row(), 3)}">
                            <input type="checkbox" name="group_popedom[${e.row().s("type_id")}][4]" value="4" lay-skin="primary" title="${page.lang().get("admin/group/popedom_down")}" checked="${page.bit(e.row(), 4)}">
                            <input type="checkbox" name="group_popedom[${e.row().s("type_id")}][5]" value="5" lay-skin="primary" title="${page.lang().get("admin/group/popedom_trysee")}" checked="${page.bit(e.row(), 5)}">
                            @endif
                        </dt>
                    </dl>
                @endfor
                </div>
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="button" class="layui-btn layui-btn-normal formCheckAll" lay-filter="formCheckAll" >${page.lang().get("check_all")}</button>
                <button type="button" class="layui-btn layui-btn-normal formCheckOther" lay-filter="formCheckOther">${page.lang().get("check_other")}</button>
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/group-info.js" data-msg-name="${page.lang().get("name_empty")}"></script>
`)
