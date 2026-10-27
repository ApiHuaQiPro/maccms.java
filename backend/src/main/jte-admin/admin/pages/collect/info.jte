@import com.maccms.backend.admin.web.view.collect.CollectInfoView
@param CollectInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="collect_id" name="collect_id" type="hidden" value="${page.v("collect_id")}">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/name")}：</label>
            <div class="layui-input-block  ">
                <input type="text" class="layui-input" value="${page.v("collect_name")}" placeholder="" id="collect_name" name="collect_name">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/api_url")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("collect_url")}" placeholder="" id="collect_url" name="collect_url">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/attach_param")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("collect_param")}" placeholder="" id="collect_param" name="collect_param">
            </div>
            <div class="layui-form-mid layui-word-aux" style="margin-left:110px; ">${page.lang().get("admin/collect/attach_param_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/api_type")}：</label>
            <div class="layui-input-block">
                <input name="collect_type" type="radio" value="1" title="xml" checked="${page.eq("collect_type", 1)}">
                <input name="collect_type" type="radio" value="2" title="json" checked="${!page.eq("collect_type", 1)}">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/data_type")}：</label>
            <div class="layui-input-block">
                <input name="collect_mid" lay-filter="collect_mid" type="radio" value="1" title="${page.lang().get("vod")}" checked="${page.eq("collect_mid", 1)}">
                <input name="collect_mid" lay-filter="collect_mid" type="radio" value="2" title="${page.lang().get("art")}" checked="${page.eq("collect_mid", 2)}">
                <input name="collect_mid" lay-filter="collect_mid" type="radio" value="8" title="${page.lang().get("actor")}" checked="${page.eq("collect_mid", 8)}">
                <input name="collect_mid" lay-filter="collect_mid" type="radio" value="9" title="${page.lang().get("role")}" checked="${page.eq("collect_mid", 9)}">
                <input name="collect_mid" lay-filter="collect_mid" type="radio" value="11" title="${page.lang().get("website")}" checked="${page.eq("collect_mid", 11)}">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/collect/data_opt")}：</label>
            <div class="layui-input-block">
                <input name="collect_opt" type="radio" value="0" title="${page.lang().get("admin/collect/add_update")}" checked="${page.eq("collect_opt", 0)}">
                <input name="collect_opt" type="radio" value="1" title="${page.lang().get("admin/collect/add")}" checked="${page.eq("collect_opt", 1)}">
                <input name="collect_opt" type="radio" value="2" title="${page.lang().get("admin/collect/update")}" checked="${page.eq("collect_opt", 2)}">
            </div>
            <div class="layui-form-mid layui-word-aux" style="">${page.lang().get("admin/collect/data_opt_tip")}</div>
        </div>

        <div class="layui-form-item row_filer" style="${page.hideFilter() ? "display:none;" : null}">
            <label class="layui-form-label">${page.lang().get("admin/collect/url_filter")}：</label>
            <div class="layui-input-block">
                <input name="collect_filter" type="radio" value="0" title="${page.lang().get("admin/collect/no_filter")}" checked="${page.eq("collect_filter", 0)}">
                <input name="collect_filter" type="radio" value="1" title="${page.lang().get("admin/collect/add_update")}" checked="${page.eq("collect_filter", 1)}">
                <input name="collect_filter" type="radio" value="2" title="${page.lang().get("admin/collect/add")}" checked="${page.eq("collect_filter", 2)}">
                <input name="collect_filter" type="radio" value="3" title="${page.lang().get("admin/collect/update")}" checked="${page.eq("collect_filter", 3)}">
            </div>
        </div>
        <div class="layui-form-item row_filer" style="${page.hideFilter() ? "display:none;" : null}">
            <label class="layui-form-label">${page.lang().get("admin/collect/filter_code")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("collect_filter_from")}" placeholder="" id="collect_filter_from" name="collect_filter_from">
            </div>
            <div class="layui-form-mid layui-word-aux" style="margin-left:110px; ">${page.lang().get("admin/collect/filter_code_tip")}</div>
        </div>
        <div class="layui-form-item row_filer">
            <label class="layui-form-label">${page.lang().get("pic_sync")}：</label>
            <div class="layui-input-block">
                <input name="collect_sync_pic_opt" type="radio" value="0" title="${page.lang().get("follow_global")}" checked="${page.eq("collect_sync_pic_opt", 0)}">
                <input name="collect_sync_pic_opt" type="radio" value="1" title="${page.lang().get("open")}" checked="${page.eq("collect_sync_pic_opt", 1)}">
                <input name="collect_sync_pic_opt" type="radio" value="2" title="${page.lang().get("close")}" checked="${page.eq("collect_sync_pic_opt", 2)}">
            </div>
        </div>

        <br>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button class="layui-btn layui-btn-normal" type="button" id="btnTest" >${page.lang().get("test")}</button>
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/collect-info.js" data-test-url="${page.testUrl()}" data-msg-name="${page.lang().get("name_empty")}" data-msg-url="${page.lang().get("url_empty")}" data-msg-test-ok="${page.lang().get("admin/collect/test_ok")}"></script>
`)
