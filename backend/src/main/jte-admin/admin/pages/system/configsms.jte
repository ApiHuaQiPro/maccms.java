@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this" lay-id="configsms_1">${page.lang().get("admin/system/configsms/title")}</li>
                @for(var item_vo : page.each(page.at("extends.ext_list")))
                <li data-key="${page.v(item_vo.key())}" lay-id="configsms_${item_vo.i() + 1}">${page.t(item_vo.value())}</li>
                @endfor
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configsms/tip")}
                    </blockquote>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configsms/type")}：</label>
                        <div class="layui-input-inline">
                            <select  name="sms[type]">
                                <option value="" >${page.lang().get("select_please")}...</option>
                                @for(var item_vo : page.each(page.at("extends.ext_list")))
                                <option value="${page.v(item_vo.key())}" selected="${page.eq(page.at("config.sms.type"), item_vo.key())}">${page.t(item_vo.value())}</option>
                                @endfor
                            </select>
                        </div>
                        <div class="layui-form-mid layui-word-aux"></div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configsms/sign")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" id="sign" name="sms[sign]" placeholder="" value="${page.v(page.at("config.sms.sign"))}" class="layui-input "  >
                        </div>
                        <div class="layui-form-mid layui-word-aux"></div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configsms/tpl_code_reg")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" id="tpl_code_reg" name="sms[tpl_code_reg]" placeholder="" value="${page.v(page.at("config.sms.tpl_code_reg"))}" class="layui-input "  >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configsms/tpl_code_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configsms/tpl_code_bind")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" id="tpl_code_bind" name="sms[tpl_code_bind]" placeholder="" value="${page.v(page.at("config.sms.tpl_code_bind"))}" class="layui-input "  >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configsms/tpl_code_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configsms/tpl_code_findpass")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" id="tpl_code_findpass" name="sms[tpl_code_findpass]" placeholder="" value="${page.v(page.at("config.sms.tpl_code_findpass"))}" class="layui-input "  >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configsms/tpl_code_tip")}</div>
                    </div>
                </div>
                @template.admin.pages.system.extend.html(page = page, flag = "sms")
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
