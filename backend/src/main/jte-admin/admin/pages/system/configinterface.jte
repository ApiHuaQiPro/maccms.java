@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configinterface/title")}</li>
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configinterface/tip")}
                    </blockquote>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="interface[status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.interface.status"), 1L)}">
                        <input type="radio" name="interface[status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.interface.status"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/pass")}：</label>
                    <div class="layui-input-inline w400">
                        <input type="text" name="interface[pass]" placeholder="" value="${page.v(page.at("config.interface.pass"))}" class="layui-input">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configinterface/pass_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/vod_type")}：</label>
                    <div class="layui-input-inline" style="width:230px;!important;">
                        <textarea name="interface[vodtype]" class="layui-textarea" rows="20" >${page.t(page.replaceText(page.at("config.interface.vodtype")))}</textarea>
                    </div>
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/art_type")}：</label>
                    <div class="layui-input-inline" style="width:230px;!important;">
                        <textarea name="interface[arttype]" class="layui-textarea" rows="20" >${page.t(page.replaceText(page.at("config.interface.arttype")))}</textarea>
                    </div>
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/actor_type")}：</label>
                    <div class="layui-input-inline" style="width:230px;!important;">
                        <textarea name="interface[actortype]" class="layui-textarea" rows="20" >${page.t(page.replaceText(page.at("config.interface.actortype")))}</textarea>
                    </div>
                    <label class="layui-form-label">${page.lang().get("admin/system/configinterface/website_type")}：</label>
                    <div class="layui-input-inline" style="width:230px;!important;">
                        <textarea name="interface[websitetype]" class="layui-textarea" rows="20" >${page.t(page.replaceText(page.at("config.interface.websitetype")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">

                </div>
            </div>
            </div>
        </div>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>
`)
