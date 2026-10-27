@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab" lay-filter="tb1">
            <ul class="layui-tab-title">
                <li class="layui-this" lay-id="configpay_1">${page.lang().get("admin/system/configpay/title")}</li>
                <li lay-id="configpay_2">${page.lang().get("admin/system/configpay/card")}</li>
                @for(var item_vo : page.each(page.at("extends.ext_list")))
                <li data-key="${page.v(item_vo.key())}" lay-id="configpay_${item_vo.i() + 2}">${page.t(item_vo.value())}</li>
                @endfor

            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                    <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                        <legend>${page.lang().get("admin/system/configpay/config")}</legend>
                    </fieldset>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configpay/notify")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" readonly="readonly" value="${page.v(page.at("http_type"))}${page.v(page.at("config.site.site_url"))}/index.php/payment/notify.html" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configpay/notify_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configpay/min")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" name="pay[min]" placeholder="" value="${page.v(page.at("config.pay.min"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configpay/min_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configpay/scale")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" name="pay[scale]" placeholder="" value="${page.v(page.at("config.pay.scale"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configpay/scale_tip")}</div>
                    </div>
                </div>

                <div class="layui-tab-item ">

                    <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                        <legend>${page.lang().get("admin/system/configpay/card_config")}</legend>
                    </fieldset>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configpay/card_url")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" name="pay[card][url]" placeholder="" value="${page.v(page.at("config.pay.card.url"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configpay/card_url_tip")}</div>
                    </div>
                </div>

                @template.admin.pages.system.extend.html(page = page, flag = "pay")

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
`, scripts = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.cookie.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/system-tab.js" data-cookie="configpay_tab"></script>
`)
