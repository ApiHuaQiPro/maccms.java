@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configuser/title")}</li>

            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configuser/tip")}
                    </blockquote>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/model")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="user[status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.status"), 1L)}">
                        <input type="radio" name="user[status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.status"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux"></div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_open")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="user[reg_open]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.reg_open"), 1L)}">
                        <input type="radio" name="user[reg_open]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.reg_open"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="user[reg_status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.user.reg_status"), 1L)}">
                        <input type="radio" name="user[reg_status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.user.reg_status"), 1L)}">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/phone_reg_verify")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[reg_phone_sms]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.reg_phone_sms"), 1L)}">
                            <input type="radio" name="user[reg_phone_sms]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.reg_phone_sms"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/email_reg_verify")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[reg_email_sms]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.reg_email_sms"), 1L)}">
                            <input type="radio" name="user[reg_email_sms]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.reg_email_sms"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/email_white_hosts")}：</label>
                        <div class="layui-input-block">
                            <textarea name="user[email_white_hosts]" class="layui-textarea" placeholder="${page.lang().get("admin/system/configuser/email_white_hosts_tip")}">${page.t(page.at("config.user.email_white_hosts"))}</textarea>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/email_black_hosts")}：</label>
                        <div class="layui-input-block">
                            <textarea name="user[email_black_hosts]" class="layui-textarea" placeholder="${page.lang().get("admin/system/configuser/email_black_hosts_tip")}">${page.t(page.at("config.user.email_black_hosts"))}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_verify")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[reg_verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.reg_verify"), 1L)}">
                            <input type="radio" name="user[reg_verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.reg_verify"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/login_verify")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[login_verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.login_verify"), 1L)}">
                            <input type="radio" name="user[login_verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.login_verify"), 1L)}">
                        </div>
                    </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_points")}：</label>
                    <div class="layui-input-inline w150">
                        <input type="text" name="user[reg_points]" placeholder="" value="${page.v(page.at("config.user.reg_points"))}" class="layui-input">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/reg_points_tip")}</div>
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_num")}：</label>
                    <div class="layui-input-inline w150">
                        <input type="text" name="user[reg_num]" placeholder="" value="${page.v(page.at("config.user.reg_num"))}" class="layui-input">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/reg_num_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configuser/invite_reg_points")}：</label>
                    <div class="layui-input-inline w150">
                        <input type="text" name="user[invite_reg_points]" placeholder="" value="${page.v(page.at("config.user.invite_reg_points"))}" class="layui-input">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/invite_reg_points_tip")}</div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/invite_visit_points")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[invite_visit_points]" placeholder="" value="${page.v(page.at("config.user.invite_visit_points"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/invite_visit_points_tip")}</div>
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reg_num")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[invite_visit_num]" placeholder="" value="${page.v(page.at("config.user.invite_visit_num"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/invite_visit_num_tip")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reward_status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[reward_status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.reward_status"), 1L)}">
                            <input type="radio" name="user[reward_status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.reward_status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reward_ratio")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[reward_ratio]" placeholder="${page.lang().get("admin/system/configuser/reward_unit")}" value="${page.v(page.at("config.user.reward_ratio"))}" class="layui-input">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reward_ratio2")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[reward_ratio_2]" placeholder="${page.lang().get("admin/system/configuser/reward_unit")}" value="${page.v(page.at("config.user.reward_ratio_2"))}" class="layui-input">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/reward_ratio3")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[reward_ratio_3]" placeholder="${page.lang().get("admin/system/configuser/reward_unit")}" value="${page.v(page.at("config.user.reward_ratio_3"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/reward_tip")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/cash_status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[cash_status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.cash_status"), 1L)}">
                            <input type="radio" name="user[cash_status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.cash_status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/cash_ratio")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[cash_ratio]" placeholder="${page.lang().get("admin/system/configuser/cash_ratio_tip")}" value="${page.v(page.at("config.user.cash_ratio"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/cash_ratio_tip")}</div>
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/cash_min")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[cash_min]" placeholder="" value="${page.v(page.at("config.user.cash_min"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/cash_min_tip")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/trysee")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[trysee]" placeholder="" value="${page.v(page.at("config.user.trysee"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/trysee_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/vod_points_type")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[vod_points_type]" value="0" title="${page.lang().get("admin/system/configuser/vod_points_0")}" checked="${!page.eq(page.at("config.user.vod_points_type"), 1L)}">
                            <input type="radio" name="user[vod_points_type]" value="1" title="${page.lang().get("admin/system/configuser/vod_points_1")}" checked="${page.eq(page.at("config.user.vod_points_type"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/art_points_type")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[art_points_type]" value="0" title="${page.lang().get("admin/system/configuser/art_points_0")}" checked="${!page.eq(page.at("config.user.art_points_type"), 1L)}">
                            <input type="radio" name="user[art_points_type]" value="1" title="${page.lang().get("admin/system/configuser/art_points_1")}" checked="${page.eq(page.at("config.user.art_points_type"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/portrait_status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="user[portrait_status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.user.portrait_status"), 1L)}">
                            <input type="radio" name="user[portrait_status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.user.portrait_status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/portrait_size")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="user[portrait_size]" placeholder="" value="${page.v(page.at("config.user.portrait_size"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configuser/portrait_size_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configuser/filter_words")}：</label>
                        <div class="layui-input-block">
                            <textarea name="user[filter_words]" class="layui-textarea" placeholder="${page.lang().get("admin/system/configuser/filter_words_tip")}">${page.t(page.at("config.user.filter_words"))}</textarea>
                        </div>
                    </div>
            </div>


                <div class="layui-form-item center">
                    <div class="layui-input-block">
                        <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                        <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
                    </div>
                </div>
            </div>
        </div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/layui-form.js"></script>
`)
