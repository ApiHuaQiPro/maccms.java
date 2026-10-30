@import com.maccms.backend.admin.web.view.member.UserInfoView
@import com.maccms.backend.content.application.view.AdminRow
@param UserInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="user_id" name="user_id" type="hidden" value="${page.v("user_id")}">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("access")}：</label>
            <div class="layui-input-block  ">
                <input type="text" class="layui-input" value="${page.v("user_name")}" placeholder="" id="user_name" name="user_name">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("pass")}：</label>
            <div class="layui-input-block">
                <input type="password" class="layui-input" value="" placeholder="" id="user_pwd" name="user_pwd">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("status")}：</label>
            <div class="layui-input-block">
                    <input name="user_status" type="radio" id="rad-1" value="0" title="${page.lang().get("disable")}" checked="${!page.enabled()}">
                    <input name="user_status" type="radio" id="rad-2" value="1" title="${page.lang().get("enable")}" checked="${page.enabled()}">
            </div>
        </div>


        <div class="layui-form-item ">
            <label class="layui-form-label">${page.lang().get("group")}：</label>
            <div class="layui-input-inline">
                <select name="group_id" lay-filter="group_id">
                    @for(AdminRow vo : page.groups())
                    <option value="${vo.s("group_id")}" selected="${page.groupSelected(vo)}">${page.text(vo.s("group_name"))}</option>
                    @endfor
                </select>
            </div>
        </div>
        @if(page.editing())
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("portrait")}：</label>
            <div class="layui-input-inline w500 upload">
                <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("user_portrait")}" placeholder="" id="user_portrait" name="user_portrait">
            </div>
            <div class="layui-input-inline ">
                <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:''}}" id="upload1">${page.lang().get("upload_pic")}</button>
            </div>
        </div>
        @endif

        <div class="layui-form-item" >
            <label class="layui-form-label">${page.lang().get("nickname")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" name="user_nick_name" value="${page.v("user_nick_name")}" placeholder="">
            </div>
        </div>

        <div class="layui-form-item rowTime" style="${page.hideEndTime() ? "display:none;" : null}">

            <label class="layui-form-label">${page.lang().get("admin/user/time_end")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" name="user_end_time" id="user_end_time" value="${page.endTime()}" placeholder="yyyy-MM-dd HH:mm:ss">
            </div>
        </div>


        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("points")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("user_points")}" placeholder="" id="user_points" name="user_points">
            </div>
            <label class="layui-form-label">${page.lang().get("phone")}：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("user_phone")}" placeholder="" id="user_phone" name="user_phone">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">QQ：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("user_qq")}" placeholder="" id="user_qq" name="user_qq">
            </div>
            <label class="layui-form-label">email：</label>
            <div class="layui-input-inline">
                <input type="text" class="layui-input" value="${page.v("user_email")}" placeholder="" id="user_email" name="user_email">
            </div>
        </div>
        <div class="layui-form-item">

        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/user/find_question")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("user_question")}" placeholder="" id="user_question" name="user_question">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/user/find_answer")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("user_answer")}" placeholder="" id="user_answer" name="user_answer">
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/user-info.js" data-upload-url="${page.uploadUrl()}" data-msg-name="${page.lang().get("admin/user/access_empty")}" data-msg-pwd="${page.lang().get("admin/user/pass_empty")}" data-msg-uploading="${page.lang().get("upload_ing")}"></script>
`)
