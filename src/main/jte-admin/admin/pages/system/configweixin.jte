@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configweixin/title")}</li>
            </ul>
            <div class="layui-tab-content">



            <div class="layui-tab-item layui-show">
                <blockquote class="layui-elem-quote layui-quote-nm">
                    ${page.lang().get("admin/system/configweixin/tip")}
                </blockquote>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="weixin[status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.weixin.status"), 1L)}">
                        <input type="radio" name="weixin[status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.weixin.status"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/duijie")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="weixin[duijie]" placeholder="${page.lang().get("admin/system/configweixin/duijie_tip")}" value="${page.v(page.at("config.weixin.duijie"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/sousuo")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="weixin[sousuo]" placeholder="${page.lang().get("admin/system/configweixin/sousuo_tip")}" value="${page.v(page.at("config.weixin.sousuo"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/token")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="weixin[token]" placeholder="${page.lang().get("admin/system/configweixin/token_tip")}" value="${page.v(page.at("config.weixin.token"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/guanzhu")}：</label>
                    <div class="layui-input-block">
                        <textarea type="text" name="weixin[guanzhu]" placeholder="${page.lang().get("admin/system/configweixin/guanzhu_tip")}" value="" class="layui-textarea">${page.t(page.at("config.weixin.guanzhu"))}</textarea>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/wuziyuan")}：</label>
                    <div class="layui-input-block">
                        <textarea type="text" name="weixin[wuziyuan]" placeholder="${page.lang().get("admin/system/configweixin/wuziyuan_tip")}" value="" class="layui-textarea">${page.t(page.at("config.weixin.wuziyuan"))}</textarea>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/wuziyuanlink")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="weixin[wuziyuanlink]" placeholder="${page.lang().get("admin/system/configweixin/wuziyuanlink_tip")}" value="${page.v(page.at("config.weixin.wuziyuanlink"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/pagelink")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="weixin[bofang]" value="0" title="${page.lang().get("admin/system/configweixin/pagelink_detail")}" checked="${!page.eq(page.at("config.weixin.bofang"), 1L)}">
                        <input type="radio" name="weixin[bofang]" value="1" title="${page.lang().get("admin/system/configweixin/pagelink_play")}" checked="${page.eq(page.at("config.weixin.bofang"), 1L)}">
                        <input type="radio" name="weixin[bofang]" value="2" title="${page.lang().get("admin/system/configweixin/pagelink_search")}" checked="${page.eq(page.at("config.weixin.bofang"), 2L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configweixin/msgtype")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="weixin[msgtype]" value="0" title="${page.lang().get("admin/system/configweixin/msgtype_pic")}" checked="${!page.eq(page.at("config.weixin.msgtype"), 1L)}">
                        <input type="radio" name="weixin[msgtype]" value="1" title="${page.lang().get("admin/system/configweixin/msgtype_font")}" checked="${page.eq(page.at("config.weixin.msgtype"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configweixin/msgtype_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configweixin/gjc")}1：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjc1]" placeholder="${page.lang().get("admin/system/configweixin/keyword")}" value="${page.v(page.at("config.weixin.gjc1"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcm1]" placeholder="${page.lang().get("admin/system/configweixin/return_text")}" value="${page.v(page.at("config.weixin.gjcm1"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjci1]" placeholder="${page.lang().get("admin/system/configweixin/return_pic")}" value="${page.v(page.at("config.weixin.gjci1"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcl1]" placeholder="${page.lang().get("admin/system/configweixin/return_link")}" value="${page.v(page.at("config.weixin.gjcl1"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configweixin/gjc")}2：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjc2]" placeholder="${page.lang().get("admin/system/configweixin/keyword")}" value="${page.v(page.at("config.weixin.gjc2"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcm2]" placeholder="${page.lang().get("admin/system/configweixin/return_text")}" value="${page.v(page.at("config.weixin.gjcm2"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjci2]" placeholder="${page.lang().get("admin/system/configweixin/return_pic")}" value="${page.v(page.at("config.weixin.gjci2"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcl2]" placeholder="${page.lang().get("admin/system/configweixin/return_link")}" value="${page.v(page.at("config.weixin.gjcl2"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configweixin/gjc")}3：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjc3]" placeholder="${page.lang().get("admin/system/configweixin/keyword")}" value="${page.v(page.at("config.weixin.gjc3"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcm3]" placeholder="${page.lang().get("admin/system/configweixin/return_text")}" value="${page.v(page.at("config.weixin.gjcm3"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjci3]" placeholder="${page.lang().get("admin/system/configweixin/return_pic")}" value="${page.v(page.at("config.weixin.gjci3"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcl3]" placeholder="${page.lang().get("admin/system/configweixin/return_link")}" value="${page.v(page.at("config.weixin.gjcl3"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configweixin/gjc")}4：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjc4]" placeholder="${page.lang().get("admin/system/configweixin/keyword")}" value="${page.v(page.at("config.weixin.gjc4"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcm4]" placeholder="${page.lang().get("admin/system/configweixin/return_text")}" value="${page.v(page.at("config.weixin.gjcm4"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjci4]" placeholder="${page.lang().get("admin/system/configweixin/return_pic")}" value="${page.v(page.at("config.weixin.gjci4"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="weixin[gjcl4]" placeholder="${page.lang().get("admin/system/configweixin/return_link")}" value="${page.v(page.at("config.weixin.gjcl4"))}" class="layui-input">
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
