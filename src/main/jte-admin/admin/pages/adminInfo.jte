@import com.maccms.backend.admin.web.view.AdminInfoView
@import com.maccms.backend.admin.web.view.AdminText
@param AdminInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="admin_id" name="admin_id" type="hidden" value="${page.adminId()}">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("id")}：</label>
            <div class="layui-input-block  ">
                <input type="text" class="layui-input" value="${page.adminName()}" placeholder="" id="admin_name" name="admin_name">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("pass")}：</label>
            <div class="layui-input-block">
                <input type="password" class="layui-input" value="" placeholder="" id="admin_pwd" name="admin_pwd">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("status")}：</label>
            <div class="layui-input-block">
                    <input name="admin_status" type="radio" id="rad-1" value="0" title="${page.lang().get("disable")}" checked="${!page.enabled()}">
                    <input name="admin_status" type="radio" id="rad-2" value="1" title="${page.lang().get("enable")}" checked="${page.enabled()}">
            </div>
        </div>

        <div class="layui-form-item ">
            <label class="layui-form-label">${page.lang().get("popedom")}：</label>
            <div class="layui-input-block">
                <blockquote class="layui-elem-quote layui-quote-nm">
                    ${AdminText.trusted(page.lang().get("admin/admin/popedom_tip"))}
                </blockquote>


                <div class="role-list-form ">
                    @for(AdminInfoView.Group vo : page.groups())
                    <dl class="role-list-form-top permission-list">
                        <dt>
                            <input type="checkbox" value="" checked="${vo.checked()}" lay-skin="primary" data-id="${vo.index()}" lay-filter="roleAuth1" title="${vo.name()}">
                        </dt>
                        <dd>
                            @for(AdminInfoView.Item sub : vo.items())
                                <input type="checkbox" value="${sub.code()}" name="admin_auth[]" checked="${sub.checked()}" readonly="${sub.readonly()}" data-pid="${vo.index()}" title="${sub.name()}" lay-skin="primary" lay-filter="roleAuth2">
                            @endfor
                        </dd>
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
<script type="text/javascript" src="${page.head().assetsPath()}/admin-info.js" data-msg-name="${page.lang().get("name_empty")}" data-msg-pass="${page.lang().get("pass_empty")}"></script>
`)
