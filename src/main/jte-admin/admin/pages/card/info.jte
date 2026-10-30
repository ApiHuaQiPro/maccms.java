@import com.maccms.backend.admin.web.view.member.CardInfoView
@param CardInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="link_id" name="link_id" type="hidden" value="">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/card/make_num")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="10" lay-verify="num" placeholder="" name="num">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("money")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="" lay-verify="money" placeholder="" name="money">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("points")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="" lay-verify="point" placeholder="" name="point">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("card_no")}${page.lang().get("rule")}：</label>
            <div class="layui-input-block">
                <input type="radio" name="role_no" value="" title="${page.lang().get("mixing")}" checked>
                <input type="radio" name="role_no" value="letter" title="${page.lang().get("abc")}" >
                <input type="radio" name="role_no" value="num" title="${page.lang().get("number")}" >
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("pass")}${page.lang().get("rule")}：</label>
            <div class="layui-input-block">
                <input type="radio" name="role_pwd" value="" title="${page.lang().get("mixing")}" checked>
                <input type="radio" name="role_pwd" value="letter" title="${page.lang().get("abc")}" >
                <input type="radio" name="role_pwd" value="num" title="${page.lang().get("number")}" >
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
<script type="text/javascript" src="${page.head().assetsPath()}/card-info.js" data-msg-num="${page.lang().get("admin/card/please_input_make_num")}" data-msg-money="${page.lang().get("admin/card/please_input_money")}" data-msg-point="${page.lang().get("admin/card/please_input_points")}"></script>
`)
