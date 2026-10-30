@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="comment_id" name="comment_id" type="hidden" value="${page.v("comment_id")}">
        <input id="comment_mid" name="comment_mid" type="hidden" value="${page.v("comment_mid")}">
        <input id="comment_rid" name="comment_rid" type="hidden" value="${page.v("comment_rid")}">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("model")}：</label>
            <div class="layui-input-inline w80">
                <input type="text" class="layui-input" value="${page.info().has("comment_mid") ? page.midText(page.info().n("comment_mid")) : ""}" readonly="readonly">
            </div>
            <label class="layui-form-label">${page.lang().get("nickname")}：</label>
            <div class="layui-input-inline w80">
                <input type="text" class="layui-input" value="${page.v("comment_name")}" readonly="readonly" name="comment_name" >
            </div>
            <label class="layui-form-label">${page.lang().get("time")}：</label>
            <div class="layui-input-inline w130">
                <input type="text" class="layui-input" value="${page.date("comment_time")}" readonly="readonly">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("up")}：</label>
            <div class="layui-input-inline w80">
                <input type="text" class="layui-input" value="${page.v("comment_up")}" name="comment_up">
            </div>
            <label class="layui-form-label">${page.lang().get("hate")}：</label>
            <div class="layui-input-inline w80">
                <input type="text" class="layui-input" value="${page.v("comment_down")}" name="comment_down">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("content")}：</label>
            <div class="layui-input-block">
                <textarea type="text" class="layui-textarea" lay-verify="comment_content" placeholder="" name="comment_content">${page.t("comment_content")}</textarea>
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
<script type="text/javascript" src="${page.head().assetsPath()}/content-verify.js" data-field="comment_content" data-msg="${page.lang().get("content_empty")}"></script>
`)
