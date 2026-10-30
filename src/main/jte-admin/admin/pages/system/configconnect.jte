@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configconnect/title")}</li>
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configconnect/tip")}
                    </blockquote>


                    <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                        <legend>${page.lang().get("admin/system/configconnect/qq")} <a target="_blank" href="http://connect.qq.com/?maccms" class="layui-btn layui-btn-primary">${page.lang().get("admin/system/configconnect/go_reg")}</a></legend>
                    </fieldset>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="connect[qq][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.connect.qq.status"), 1L)}">
                            <input type="radio" name="connect[qq][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.connect.qq.status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">APP_KEY：</label>
                        <div class="layui-input-block">
                            <input type="text" name="connect[qq][key]" placeholder="" value="${page.v(page.at("config.connect.qq.key"))}" class="layui-input">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">APP_SECRET：</label>
                        <div class="layui-input-block">
                            <input type="text" name="connect[qq][secret]" placeholder="" value="${page.v(page.at("config.connect.qq.secret"))}" class="layui-input">
                        </div>
                    </div>


                    <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                        <legend>${page.lang().get("admin/system/configconnect/wx")} <a target="_blank" href="https://open.weixin.qq.com/?maccms" class="layui-btn layui-btn-primary">${page.lang().get("admin/system/configconnect/go_reg")}</a></legend>
                    </fieldset>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="connect[weixin][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.connect.weixin.status"), 1L)}">
                            <input type="radio" name="connect[weixin][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.connect.weixin.status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">APP_KEY：</label>
                        <div class="layui-input-block">
                            <input type="text" name="connect[weixin][key]" placeholder="" value="${page.v(page.at("config.connect.weixin.key"))}" class="layui-input">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">APP_SECRET：</label>
                        <div class="layui-input-block">
                            <input type="text" name="connect[weixin][secret]" placeholder="" value="${page.v(page.at("config.connect.weixin.secret"))}" class="layui-input">
                        </div>
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
