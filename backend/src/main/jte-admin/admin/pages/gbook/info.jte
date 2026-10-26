@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="gbook_id" name="gbook_id" type="hidden" value="${page.v("gbook_id")}">
        <input id="gbook_rid" name="gbook_rid" type="hidden" value="${page.v("gbook_rid")}">
        <div class="layui-form-item">

            <label class="layui-form-label">昵称：</label>
            <div class="layui-input-inline w80">
                <input type="text" class="layui-input" value="${page.v("gbook_name")}" readonly="readonly" name="gbook_name" >
            </div>
            <label class="layui-form-label">留言时间：</label>
            <div class="layui-input-inline w130">
                <input type="text" class="layui-input" value="${page.date("gbook_time")}" readonly="readonly">
            </div>
            <label class="layui-form-label">回复时间：</label>
            <div class="layui-input-inline w130">
                <input type="text" class="layui-input" value="${page.date("gbook_reply_time")}" readonly="readonly">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">留言内容：</label>
            <div class="layui-input-block">
                <textarea type="text" class="layui-textarea" lay-verify="gbook_content" placeholder="请输入评论内容" name="gbook_content">${page.t("gbook_content")}</textarea>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">回复内容：</label>
            <div class="layui-input-block">
                <textarea type="text" class="layui-textarea" lay-verify="gbook_reply" placeholder="请输入回复内容" name="gbook_reply">${page.t("gbook_reply")}</textarea>
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
<script type="text/javascript" src="${page.head().assetsPath()}/content-verify.js" data-field="gbook_content" data-msg="请输入留言内容"></script>
`)
