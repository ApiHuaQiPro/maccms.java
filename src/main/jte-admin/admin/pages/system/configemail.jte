@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab" lay-filter="tb1">
            <ul class="layui-tab-title">
                <li class="layui-this" lay-id="configemail_1">${page.lang().get("admin/system/configemail/title")}</li>
                @for(var item_vo : page.each(page.at("extends.ext_list")))
                <li data-key="${page.v(item_vo.key())}" lay-id="configemail_${item_vo.i() + 1}">${page.t(item_vo.value())}</li>
                @endfor
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configemail/tip")}
                    </blockquote>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/type")}：</label>
                        <div class="layui-input-inline">
                            <select class="w150" id="ac" name="email[type]" lay-filter="ac">
                                <option value="" >${page.lang().get("select_please")}...</option>
                                @for(var item_vo : page.each(page.at("extends.ext_list")))
                                <option value="${page.v(item_vo.key())}" selected="${page.eq(page.at("config.email.type"), item_vo.key())}">${page.t(item_vo.value())}</option>
                                @endfor
                            </select>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/time")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="email[time]" placeholder="" value="${page.v(page.at("config.email.time"))}" class="layui-input w200"  >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configemail/time_tip")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/nick")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" id="nick" name="email[nick]" placeholder="" value="${page.v(page.at("config.email.nick"))}" class="layui-input w200"  >
                        </div>
                        <div class="layui-form-mid layui-word-aux"></div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/test")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" id="test" name="email[test]" placeholder="" value="${page.v(page.at("config.email.test"))}" class="layui-input w200"  >
                        </div>
                        <button type="button" class="layui-btn layui-btn-normal" id="btn_test_email">${page.lang().get("admin/system/configemail/btn_test")}</button>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/test_title")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="email[tpl][test_title]" placeholder="" value="${page.v(page.at("config.email.tpl.test_title"))}" class="layui-input"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/test_body")}：</label>
                        <div class="layui-input-block">
                            <textarea name="email[tpl][test_body]" class="layui-textarea">${page.t(page.at("config.email.tpl.test_body"))}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_reg_title")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="email[tpl][user_reg_title]" placeholder="" value="${page.v(page.at("config.email.tpl.user_reg_title"))}" class="layui-input"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_reg_body")}：</label>
                        <div class="layui-input-block">
                            <textarea name="email[tpl][user_reg_body]" class="layui-textarea">${page.t(page.at("config.email.tpl.user_reg_body"))}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_bind_title")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="email[tpl][user_bind_title]" placeholder="" value="${page.v(page.at("config.email.tpl.user_bind_title"))}" class="layui-input"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_bind_body")}：</label>
                        <div class="layui-input-block">
                            <textarea name="email[tpl][user_bind_body]" class="layui-textarea">${page.t(page.at("config.email.tpl.user_bind_body"))}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_findpass_title")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="email[tpl][user_findpass_title]" placeholder="" value="${page.v(page.at("config.email.tpl.user_findpass_title"))}" class="layui-input"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configemail/user_findpass_body")}：</label>
                        <div class="layui-input-block">
                            <textarea name="email[tpl][user_findpass_body]" class="layui-textarea">${page.t(page.at("config.email.tpl.user_findpass_body"))}</textarea>
                        </div>
                    </div>
                </div>
                @template.admin.pages.system.extend.html(page = page, flag = "email")
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
<script type="text/javascript" src="${page.head().assetsPath()}/system-configemail.js" data-test-url="${page.url("system/test_email")}" data-msg-wait="${page.lang().get("wait_submit")}" data-msg-test-err="${page.lang().get("admin/system/configemail/test_err")}"></script>
`)
