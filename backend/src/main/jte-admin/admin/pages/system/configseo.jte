@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
        <form class="layui-form layui-form-pane" action="">
            <input type="hidden" name="__token__" value="${page.token()}" />
            <div class="layui-tab" lay-filter="tb1">
                <ul class="layui-tab-title">
                    <li class="layui-this" lay-id="configseo_1">${page.lang().get("admin/system/configseo/vod_index")}SEO</li>
                    <li lay-id="configseo_2">${page.lang().get("admin/system/configseo/art_index")}SEO</li>
                    <li lay-id="configseo_3">${page.lang().get("admin/system/configseo/actor_index")}SEO</li>
                    <li lay-id="configseo_4">${page.lang().get("admin/system/configseo/role_index")}SEO</li>
                    <li lay-id="configseo_5">${page.lang().get("admin/system/configseo/plot_index")}SEO</li>
                    <li lay-id="configseo_6">${page.lang().get("admin/system/configseo/website_index")}SEO</li>
                </ul>
                <div class="layui-tab-content">
                    <div class="layui-tab-item layui-show">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/vod_index")} vod/index
                        </blockquote>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configseo/tit")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="seo[vod][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.vod.name"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configseo/key")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="seo[vod][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.vod.key"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configseo/des")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="seo[vod][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.vod.des"))}" class="layui-input">
                    </div>
                </div>

            </div>
                    <div class="layui-tab-item">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/art_index")} art/index
                        </blockquote>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/tit")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[art][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.art.name"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/key")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[art][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.art.key"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/des")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[art][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.art.des"))}" class="layui-input">
                            </div>
                        </div>

                    </div>


                    <div class="layui-tab-item">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/actor_index")} actor/index
                        </blockquote>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/tit")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[actor][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.actor.name"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/key")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[actor][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.actor.key"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/des")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[actor][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.actor.des"))}" class="layui-input">
                            </div>
                        </div>

                    </div>

                    <div class="layui-tab-item">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/role_index")} role/index
                        </blockquote>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/tit")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[role][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.role.name"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/key")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[role][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.role.key"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/des")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[role][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.role.des"))}" class="layui-input">
                            </div>
                        </div>

                    </div>

                    <div class="layui-tab-item">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/plot_index")} plot/index
                        </blockquote>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/tit")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[plot][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.plot.name"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/key")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[plot][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.plot.key"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/des")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[plot][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.plot.des"))}" class="layui-input">
                            </div>
                        </div>

                    </div>


                    <div class="layui-tab-item">
                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configseo/tip_des")}：<br>
                            ${page.lang().get("admin/system/configseo/website_index")} website/index
                        </blockquote>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/tit")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[website][name]" placeholder="${page.lang().get("admin/system/configseo/tit")}title" value="${page.v(page.at("config.seo.website.name"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/key")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[website][key]" placeholder="${page.lang().get("admin/system/configseo/key")}keywords" value="${page.v(page.at("config.seo.website.key"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configseo/des")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="seo[website][des]" placeholder="${page.lang().get("admin/system/configseo/des")}description" value="${page.v(page.at("config.seo.website.des"))}" class="layui-input">
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
`, scripts = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.cookie.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/system-tab.js" data-cookie="configapi_tab"></script>
`)
