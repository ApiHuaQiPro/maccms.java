@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <form class="layui-form " method="post" action="${page.url().build("sync")}">
        <input type="hidden" name="tab" value="${page.attr(page.xs("tab"))}">
        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("admin/images/sync_range")}</legend>
            <div class="layui-field-box">
                <div class="layui-form-item">
                        <div class="layui-input-inline" style="width: 110px;">
                            <input type="radio" checked value="1" name="range" title="${page.lang().get("all")}">
                        </div>
                        <div class="layui-input-inline" style="width: 110px;">
                            <input type="radio" value="2" name="range" title="${page.lang().get("admin/images/date")}">
                        </div>
                        <div class="layui-input-inline" style="width: 100px;">
                            <input type="text" name="date" required  placeholder="" autocomplete="off" class="layui-input" value="${page.xs("today")}">
                        </div>
                </div>
            </div>
        </fieldset>

        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("admin/images/sync_option")}</legend>
            <div class="layui-field-box">
                <div class="layui-form-item">
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="radio"  value="0" name="opt" title="${page.lang().get("all")}">
                    </div>
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="radio" value="1" name="opt" title="${page.lang().get("not_pic_sync_err")}">
                    </div>
                    <div class="layui-input-inline" style="width: 120px;">
                        <input type="radio" checked value="2" name="opt" title="${page.lang().get("not_pic_sync_today_err")}">
                    </div>
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="radio" value="3" name="opt" title="${page.lang().get("pic_err")}">
                    </div>
                </div>
            </div>
        </fieldset>

        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("admin/images/opt/tip1")}</legend>
            <div class="layui-field-box">
                <div class="layui-form-item">
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="radio" checked value="1" name="col" title="${page.lang().get("admin/images/opt/pic")}">
                    </div>
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="radio" value="2" name="col" title="${page.lang().get("admin/images/pic_content")}">
                    </div>
                </div>
            </div>
        </fieldset>

        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("admin/images/opt/tip2")}</legend>
            <div class="layui-field-box">
                <div class="layui-form-item">
                    <div class="layui-input-inline" style="width: 110px;">
                        <input type="text" name="limit" required placeholder="" autocomplete="off" class="layui-input" value="10">
                    </div>
                </div>
            </div>
        </fieldset>

        <div class="layui-form-item">
            <button type="submit" class="layui-btn btn_submit">${page.lang().get("start_exec")}</button>
        </div>

    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/batch-form.js"></script>
`)
