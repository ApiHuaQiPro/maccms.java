@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
        <form class="layui-form layui-form-pane" action="">
            <input type="hidden" name="__token__" value="${page.token()}" />
            <div class="layui-tab" lay-filter="tb1">
                <ul class="layui-tab-title">
                    <li class="layui-this" lay-id="configapi_1">${page.lang().get("admin/system/configapi/vod")}</li>
                    <li lay-id="configapi_2">${page.lang().get("admin/system/configapi/art")}</li>
                    <li lay-id="configapi_3">${page.lang().get("admin/system/configapi/actor")}</li>
                    <li lay-id="configapi_4">${page.lang().get("admin/system/configapi/role")}</li>
                    <li lay-id="configapi_5">${page.lang().get("admin/system/configapi/website")}</li>
                </ul>
                <div class="layui-tab-content">
                    <div class="layui-tab-item layui-show">

                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configapi/vod_tip")}
                        </blockquote>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configapi/status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="api[vod][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.vod.status"), 1L)}">
                        <input type="radio" name="api[vod][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.vod.status"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configapi/charge")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="api[vod][charge]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.vod.charge"), 1L)}">
                        <input type="radio" name="api[vod][charge]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.vod.charge"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configapi/detail_inc_hits")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="radio" name="api[vod][detail_inc_hits]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.vod.detail_inc_hits"), 1L)}">
                        <input type="radio" name="api[vod][detail_inc_hits]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.vod.detail_inc_hits"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configapi/detail_inc_hits_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configapi/pagesize")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="api[vod][pagesize]" placeholder="${page.lang().get("admin/system/configapi/pagesize_tip")}" value="${page.v(page.at("config.api.vod.pagesize"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configapi/imgurl")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="api[vod][imgurl]" placeholder="${page.lang().get("admin/system/configapi/imgurl_tip")}" value="${page.v(page.at("config.api.vod.imgurl"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configapi/typefilter")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="api[vod][typefilter]" placeholder="${page.lang().get("admin/system/configapi/typefilter_tip")}" value="${page.v(page.at("config.api.vod.typefilter"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configapi/datafilter")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="api[vod][datafilter]" placeholder="${page.lang().get("admin/system/configapi/datafilter_tip")}" value="${page.v(page.at("config.api.vod.datafilter"))}" class="layui-input">
                    </div>
                </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/cachetime")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[vod][cachetime]" placeholder="${page.lang().get("admin/system/configapi/cachetime_tip")}" value="${page.v(page.at("config.api.vod.cachetime"))}" class="layui-input">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/from")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[vod][from]" placeholder="${page.lang().get("admin/system/configapi/from_tip")}" value="${page.v(page.at("config.api.vod.from"))}" class="layui-input">
                            </div>
                        </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configapi/auth")}：</label>
                    <div class="layui-input-block">
                        <textarea name="api[vod][auth]" class="layui-textarea">${page.t(page.replaceText(page.at("config.api.vod.auth")))}</textarea>
                    </div>
                </div>

            </div>

                    <div class="layui-tab-item">

                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configapi/art_tip")}
                        </blockquote>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/status")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[art][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.art.status"), 1L)}">
                                <input type="radio" name="api[art][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.art.status"), 1L)}">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/charge")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[art][charge]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.art.charge"), 1L)}">
                                <input type="radio" name="api[art][charge]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.art.charge"), 1L)}">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/pagesize")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[art][pagesize]" placeholder="${page.lang().get("admin/system/configapi/pagesize_tip")}" value="${page.v(page.at("config.api.art.pagesize"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/imgurl")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[art][imgurl]" placeholder="${page.lang().get("admin/system/configapi/imgurl_tip")}" value="${page.v(page.at("config.api.art.imgurl"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/typefilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[art][typefilter]" placeholder="${page.lang().get("admin/system/configapi/typefilter_tip")}" value="${page.v(page.at("config.api.art.typefilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/datafilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[art][datafilter]" placeholder="${page.lang().get("admin/system/configapi/datafilter_tip_art")}" value="${page.v(page.at("config.api.art.datafilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/cachetime")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[art][cachetime]" placeholder="${page.lang().get("admin/system/configapi/cachetime_tip")}" value="${page.v(page.at("config.api.art.cachetime"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/auth")}：</label>
                            <div class="layui-input-block">
                                <textarea name="api[art][auth]" class="layui-textarea">${page.t(page.replaceText(page.at("config.api.art.auth")))}</textarea>
                            </div>
                        </div>

                    </div>

                    <div class="layui-tab-item">

                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configapi/actor_tip")}
                        </blockquote>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/status")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[actor][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.actor.status"), 1L)}">
                                <input type="radio" name="api[actor][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.actor.status"), 1L)}">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/charge")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[actor][charge]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.actor.charge"), 1L)}">
                                <input type="radio" name="api[actor][charge]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.actor.charge"), 1L)}">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/pagesize")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[actor][pagesize]" placeholder="${page.lang().get("admin/system/configapi/pagesize_tip")}" value="${page.v(page.at("config.api.actor.pagesize"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/imgurl")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[actor][imgurl]" placeholder="${page.lang().get("admin/system/configapi/imgurl_tip")}" value="${page.v(page.at("config.api.actor.imgurl"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/typefilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[actor][typefilter]" placeholder="${page.lang().get("admin/system/configapi/typefilter_tip")}" value="${page.v(page.at("config.api.actor.typefilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/datafilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[actor][datafilter]" placeholder="${page.lang().get("admin/system/configapi/datafilter_tip_actor")}" value="${page.v(page.at("config.api.actor.datafilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/cachetime")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[actor][cachetime]" placeholder="${page.lang().get("admin/system/configapi/cachetime_tip")}" value="${page.v(page.at("config.api.actor.cachetime"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/auth")}：</label>
                            <div class="layui-input-block">
                                <textarea name="api[actor][auth]" class="layui-textarea">${page.t(page.replaceText(page.at("config.api.actor.auth")))}</textarea>
                            </div>
                        </div>

                    </div>

                    <div class="layui-tab-item">

                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configapi/role_tip")}
                        </blockquote>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/status")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[role][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.role.status"), 1L)}">
                                <input type="radio" name="api[role][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.role.status"), 1L)}">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/charge")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[role][charge]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.role.charge"), 1L)}">
                                <input type="radio" name="api[role][charge]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.role.charge"), 1L)}">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/pagesize")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[role][pagesize]" placeholder="${page.lang().get("admin/system/configapi/pagesize_tip")}" value="${page.v(page.at("config.api.role.pagesize"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/imgurl")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[role][imgurl]" placeholder="${page.lang().get("admin/system/configapi/imgurl_tip")}" value="${page.v(page.at("config.api.role.imgurl"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/typefilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[role][typefilter]" placeholder="${page.lang().get("admin/system/configapi/typefilter_tip")}" value="${page.v(page.at("config.api.role.typefilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/datafilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[role][datafilter]" placeholder="${page.lang().get("admin/system/configapi/datafilter_tip_role")}" value="${page.v(page.at("config.api.role.datafilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/cachetime")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[role][cachetime]" placeholder="${page.lang().get("admin/system/configapi/cachetime_tip")}" value="${page.v(page.at("config.api.role.cachetime"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/auth")}：</label>
                            <div class="layui-input-block">
                                <textarea name="api[role][auth]" class="layui-textarea">${page.t(page.replaceText(page.at("config.api.role.auth")))}</textarea>
                            </div>
                        </div>

                    </div>

                    <div class="layui-tab-item">

                        <blockquote class="layui-elem-quote layui-quote-nm">
                            ${page.lang().get("admin/system/configapi/website_tip")}
                        </blockquote>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/status")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[website][status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.website.status"), 1L)}">
                                <input type="radio" name="api[website][status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.website.status"), 1L)}">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/charge")}：</label>
                            <div class="layui-input-block">
                                <input type="radio" name="api[website][charge]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.api.website.charge"), 1L)}">
                                <input type="radio" name="api[website][charge]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.api.website.charge"), 1L)}">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/pagesize")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[website][pagesize]" placeholder="${page.lang().get("admin/system/configapi/pagesize_tip")}" value="${page.v(page.at("config.api.website.pagesize"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/imgurl")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[website][imgurl]" placeholder="${page.lang().get("admin/system/configapi/imgurl_tip")}" value="${page.v(page.at("config.api.website.imgurl"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/typefilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[website][typefilter]" placeholder="${page.lang().get("admin/system/configapi/typefilter_tip")}" value="${page.v(page.at("config.api.website.typefilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/datafilter")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[website][datafilter]" placeholder="${page.lang().get("admin/system/configapi/datafilter_tip_website")}" value="${page.v(page.at("config.api.website.datafilter"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">
                                ${page.lang().get("admin/system/configapi/cachetime")}：</label>
                            <div class="layui-input-block">
                                <input type="text" name="api[website][cachetime]" placeholder="${page.lang().get("admin/system/configapi/cachetime_tip")}" value="${page.v(page.at("config.api.website.cachetime"))}" class="layui-input">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/system/configapi/auth")}：</label>
                            <div class="layui-input-block">
                                <textarea name="api[website][auth]" class="layui-textarea">${page.t(page.replaceText(page.at("config.api.website.auth")))}</textarea>
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
