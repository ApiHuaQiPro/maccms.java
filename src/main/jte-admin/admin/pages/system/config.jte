@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />

        <div class="layui-tab" lay-filter="tb1">
            <ul class="layui-tab-title">
                <li class="layui-this" lay-id="config_1">${page.lang().get("admin/system/config/base")}</li>
                <li lay-id="config_2">${page.lang().get("admin/system/config/performance")}</li>
                <li lay-id="config_3">${page.lang().get("admin/system/config/parameters")}</li>
                <li lay-id="config_4">${page.lang().get("admin/system/config/backstage")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_name")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_name]" placeholder="" value="${page.v(page.at("config.site.site_name"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_url")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_url]" placeholder="${page.lang().get("admin/system/config/site_url_tip")}" value="${page.v(page.at("config.site.site_url"))}" class="layui-input">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/site_wapurl")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="site[site_wapurl]" placeholder="${page.lang().get("admin/system/config/site_wapurl_tip")}" value="${page.v(page.at("config.site.site_wapurl"))}" class="layui-input">
                        </div>
                    </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_keywords")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_keywords]" placeholder="" value="${page.v(page.at("config.site.site_keywords"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_description")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_description]" placeholder="" value="${page.v(page.at("config.site.site_description"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_icp")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_icp]" placeholder="" value="${page.v(page.at("config.site.site_icp"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_qq")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_qq]" placeholder="" value="${page.v(page.at("config.site.site_qq"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">QQ联系地址</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_qq_link]" placeholder="" value="${page.v(page.at("config.site.site_qq_link"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">Telegram</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_telegram]" placeholder="" value="${page.v(page.at("config.site.site_telegram"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">Telegram联系方式</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_telegram_link]" placeholder="" value="${page.v(page.at("config.site.site_telegram_link"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_email")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[site_email]" placeholder="" value="${page.v(page.at("config.site.site_email"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/install_dir")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="site[install_dir]" placeholder="${page.lang().get("admin/system/config/install_dir_tip")}" value="${page.v(page.at("config.site.install_dir"))}" class="layui-input">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/site_logo")}：</label>
                        <div class="layui-input-inline w600">
                            <input type="text" name="site[site_logo]" placeholder="${page.lang().get("admin/system/config/site_logo_tip")}" value="${page.v(page.at("config.site.site_logo"))}" class="layui-input upload-input">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'site[site_logo]'}}" id="upload1">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/site_waplogo")}：</label>
                        <div class="layui-input-inline w600">
                            <input type="text" name="site[site_waplogo]" placeholder="${page.lang().get("admin/system/config/site_logo_tip")}" value="${page.v(page.at("config.site.site_waplogo"))}" class="layui-input upload-input">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'upload-thumb'}}" id="upload2">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/template_dir")}：</label>
                    <div class="layui-input-inline" >
                            <select class="w150" name="site[template_dir]">
                                @for(var item_vo : page.each(page.at("templates")))
                                <option value="${page.v(item_vo.value())}" selected="${page.eq(page.at("config.site.template_dir"), item_vo.value())}">${page.t(item_vo.value())}</option>
                                @endfor
                            </select>
                    </div>
                    <label class="layui-form-label">${page.lang().get("admin/system/config/html_dir")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="site[html_dir]" placeholder="" value="${page.v(page.at("config.site.html_dir"))}" class="layui-input w150" >
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/mob_status")}：</label>
                    <div class="layui-input-inline w300">
                        <input type="radio" name="site[mob_status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.site.mob_status"), 1L)}">
                        <input type="radio" name="site[mob_status]" value="1" title="${page.lang().get("admin/system/config/mob_multiple")}" checked="${page.eq(page.at("config.site.mob_status"), 1L)}">
                        <input type="radio" name="site[mob_status]" value="2" title="${page.lang().get("admin/system/config/mob_one")}" checked="${page.eq(page.at("config.site.mob_status"), 2L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/mob_status_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/mob_template_dir")}：</label>
                    <div class="layui-input-inline">
                            <select class="w150" name="site[mob_template_dir]">
                                @for(var item_vo : page.each(page.at("templates")))
                                <option value="${page.v(item_vo.value())}" selected="${page.eq(page.at("config.site.mob_template_dir"), item_vo.value())}">${page.t(item_vo.value())}</option>
                                @endfor
                            </select>
                    </div>
                    <label class="layui-form-label">${page.lang().get("admin/system/config/html_dir")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="site[mob_html_dir]" placeholder="" value="${page.v(page.at("config.site.mob_html_dir"))}" class="layui-input w150" >
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/site_tj")}：</label>
                    <div class="layui-input-block">
                        <textarea name="site[site_tj]" class="layui-textarea"  placeholder="">${page.t(page.at("config.site.site_tj"))}</textarea>
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/site_status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="site[site_status]" value="0" title="${page.lang().get("close")}" checked="${page.eq(page.at("config.site.site_status"), 0L)}">
                            <input type="radio" name="site[site_status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.site.site_status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/site_close_tip")}：</label>
                        <div class="layui-input-block">
                            <textarea name="site[site_close_tip]" class="layui-textarea"  placeholder="">${page.t(page.at("config.site.site_close_tip"))}</textarea>
                        </div>
                    </div>
            </div>

                <div class="layui-tab-item">
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/pathinfo_depr")}：</label>
                        <div class="layui-input-inline w150">
                            <select class="w150" name="app[pathinfo_depr]">
                                <option value="/" selected="${page.eq(page.at("config.app.pathinfo_depr"), "/")}">${page.lang().get("admin/system/config/xg")}</option>
                                <option value="-" selected="${page.eq(page.at("config.app.pathinfo_depr"), "-")}">${page.lang().get("admin/system/config/zhx")}</option>
                                <option value="_" selected="${page.eq(page.at("config.app.pathinfo_depr"), "_")}">${page.lang().get("admin/system/config/xhx")}</option>
                            </select>
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/pathinfo_depr_tip")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/suffix")}：</label>
                        <div class="layui-input-inline">
                            <select style="width:150px;" name="app[suffix]">
                                <option value="html" selected="${page.eq(page.at("config.app.suffix"), "html")}">html</option>
                                <option value="htm" selected="${page.eq(page.at("config.app.suffix"), "htm")}">htm</option>
                            </select>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/popedom_filter")}：</label>
                        <div class="layui-input-inline">
                            <input type="radio" name="app[popedom_filter]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.popedom_filter"), 1L)}">
                            <input type="radio" name="app[popedom_filter]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.popedom_filter"), 1L)}">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/popedom_filter_tip")}</div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/cache_type")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="app[cache_type]" lay-filter="cache_type" value="file" title="file" checked="${page.eq(page.at("config.app.cache_type"), "0") || page.eq(page.at("config.app.cache_type"), "file")}">
                            <input type="radio" name="app[cache_type]" lay-filter="cache_type" value="memcache" title="memcache" checked="${page.eq(page.at("config.app.cache_type"), "1") || page.eq(page.at("config.app.cache_type"), "memcache")}">
                            <input type="radio" name="app[cache_type]" lay-filter="cache_type" value="redis" title="redis" checked="${page.eq(page.at("config.app.cache_type"), "2") || page.eq(page.at("config.app.cache_type"), "redis")}">
                            <input type="radio" name="app[cache_type]" lay-filter="cache_type" value="memcached" title="memcached" checked="${page.eq(page.at("config.app.cache_type"), "3") || page.eq(page.at("config.app.cache_type"), "memcached")}">
                        </div>
                    </div>

                    <div class="layui-form-item row_cache_server " style="${page.eq(page.at("config.app.cache_type"), "0") || page.eq(page.at("config.app.cache_type"), "file") ? "display:none;" : null}">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/cache_host")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="app[cache_host]" placeholder="${page.lang().get("admin/system/config/cache_host_tip")}" value="${page.v(page.at("config.app.cache_host"))}" class="layui-input" >
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/system/config/cache_port")}：</label>
                        <div class="layui-input-inline w150">
                            <input type="text" name="app[cache_port]" placeholder="${page.lang().get("admin/system/config/cache_port_tip")}" value="${page.v(page.at("config.app.cache_port"))}" class="layui-input" >
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/system/config/cache_username")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="app[cache_username]" placeholder="${page.lang().get("admin/system/config/cache_username_tip")}" value="${page.v(page.at("config.app.cache_username"))}" class="layui-input" >
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/system/config/cache_password")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="app[cache_password]" placeholder="${page.lang().get("admin/system/config/cache_password_tip")}" value="${page.v(page.at("config.app.cache_password"))}" class="layui-input" >
                        </div>
                        <button type="button" class="layui-btn layui-btn-normal" id="btn_test_cache">${page.lang().get("admin/system/config/cache_test")}</button>
                    </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/cache_flag")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[cache_flag]" placeholder="${page.lang().get("admin/system/config/cache_flag_auto")}" value="${page.v(page.at("config.app.cache_flag"))}" class="layui-input w150" >
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/cache_flag_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/cache_core")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[cache_core]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.cache_core"), 1L)}">
                        <input type="radio" name="app[cache_core]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.cache_core"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/cache_time")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[cache_time]" placeholder="" value="${page.v(page.at("config.app.cache_time"))}" class="layui-input w150" >
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/cache_time_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/cache_page")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[cache_page]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.cache_page"), 1L)}">
                        <input type="radio" name="app[cache_page]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.cache_page"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/cache_time_page")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[cache_time_page]" placeholder="" value="${page.v(page.at("config.app.cache_time_page"))}" class="layui-input w150" >
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/cache_time_tip")}</div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/compress")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="app[compress]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.compress"), 1L)}">
                            <input type="radio" name="app[compress]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.compress"), 1L)}">
                        </div>
                    </div>
                <div class="layui-form-item layui-hide">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/input_type")}：</label>
                    <div class="layui-input-inline w600">
                        <input type="radio" name="app[input_type]" value="1" title="${page.lang().get("get+post")}" checked="${!page.eq(page.at("config.app.input_type"), 0L)}">
                        <input type="radio" name="app[input_type]" value="0" title="${page.lang().get("get")}" checked="${page.eq(page.at("config.app.input_type"), 0L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/input_type_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/ajax_page")}：</label>
                    <div class="layui-input-inline w600">
                        <input type="radio" name="app[ajax_page]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.ajax_page"), 1L)}">
                        <input type="radio" name="app[ajax_page]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.ajax_page"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/ajax_page_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/wall_filter")}：</label>
                    <div class="layui-input-inline w600">
                        <input type="radio" name="app[wall_filter]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.wall_filter"), 1L) && !page.eq(page.at("config.app.wall_filter"), 2L)}">
                        <input type="radio" name="app[wall_filter]" value="1" title="${page.lang().get("admin/system/config/wall_unicode")}" checked="${page.eq(page.at("config.app.wall_filter"), 1L)}">
                        <input type="radio" name="app[wall_filter]" value="2" title="${page.lang().get("admin/system/config/wall_blank")}" checked="${page.eq(page.at("config.app.wall_filter"), 2L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/wall_filter_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/show")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[show]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.show"), 1L)}">
                        <input type="radio" name="app[show]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.show"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/show_verify")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[show_verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.show_verify"), 1L)}">
                        <input type="radio" name="app[show_verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.show_verify"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[search]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.search"), 1L)}">
                        <input type="radio" name="app[search]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.search"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search_verify")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[search_verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.app.search_verify"), 1L)}">
                        <input type="radio" name="app[search_verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.search_verify"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search_len")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[search_len]" placeholder="" value="${page.v(page.at("config.app.search_len"))}" class="layui-input w150">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/search_len_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search_timespan")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[search_timespan]" placeholder="" value="${page.v(page.at("config.app.search_timespan"))}" class="layui-input w150">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/search_timespan_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search_vod_rule")}：</label>
                    <div class="layui-input-inline w600">
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_name" title="${page.lang().get("name")}" checked disabled>
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_en" title="${page.lang().get("en")}" checked="${page.has(page.at("config.app.search_vod_rule"), "vod_en")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_sub" title="${page.lang().get("sub")}" checked="${page.has(page.at("config.app.search_vod_rule"), "vod_sub")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_tag" title="tag" checked="${page.has(page.at("config.app.search_vod_rule"), "vod_tag")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_actor" title="${page.lang().get("actor")}" checked="${page.has(page.at("config.app.search_vod_rule"), "vod_actor")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_vod_rule][]" value="vod_director" title="${page.lang().get("director")}" checked="${page.has(page.at("config.app.search_vod_rule"), "vod_director")}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/search_rule_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/search_art_rule")}：</label>
                    <div class="layui-input-inline w600">
                        <input type="checkbox" lay-skin="primary" name="app[search_art_rule][]" value="art_name" title="${page.lang().get("name")}" checked disabled>
                        <input type="checkbox" lay-skin="primary" name="app[search_art_rule][]" value="art_en" title="${page.lang().get("en")}" checked="${page.has(page.at("config.app.search_art_rule"), "art_en")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_art_rule][]" value="art_sub" title="${page.lang().get("sub")}" checked="${page.has(page.at("config.app.search_art_rule"), "art_sub")}">
                        <input type="checkbox" lay-skin="primary" name="app[search_art_rule][]" value="art_tag" title="tag" checked="${page.has(page.at("config.app.search_art_rule"), "art_tag")}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/search_rule_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/vod_search_optimise")}：</label>
                    <div class="layui-input-inline">
                        <input type="checkbox" lay-skin="primary" name="app[vod_search_optimise][]" value="frontend" title="${page.lang().get("admin/system/config/vod_search_optimise/frontend")}" checked="${page.has(page.at("config.app.vod_search_optimise"), "frontend")}">
                        <input type="checkbox" lay-skin="primary" name="app[vod_search_optimise][]" value="collect" title="${page.lang().get("admin/system/config/vod_search_optimise/collect")}" checked="${page.has(page.at("config.app.vod_search_optimise"), "collect")}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/vod_search_optimise_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/vod_search_optimise_cache_minutes")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[vod_search_optimise_cache_minutes]" placeholder="" value="${page.v(page.at("config.app.vod_search_optimise_cache_minutes"))}" class="layui-input w150">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/vod_search_optimise_cache_minutes_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/copyright_status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[copyright_status]" value="0" title="${page.lang().get("close")}" checked="${page.eq(page.at("config.app.copyright_status"), 0L)}">
                        <input type="radio" name="app[copyright_status]" value="1" title="${page.lang().get("admin/system/config/copyright_msg")}" checked="${page.eq(page.at("config.app.copyright_status"), 1L)}">
                        <input type="radio" name="app[copyright_status]" value="2" title="${page.lang().get("admin/system/config/copyright_jump_detail")}" checked="${page.eq(page.at("config.app.copyright_status"), 2L)}">
                        <input type="radio" name="app[copyright_status]" value="3" title="${page.lang().get("admin/system/config/copyright_jump_play")}" checked="${page.eq(page.at("config.app.copyright_status"), 3L)}">
                        <input type="radio" name="app[copyright_status]" value="4" title="${page.lang().get("admin/system/config/copyright_jump_iframe")}" checked="${page.eq(page.at("config.app.copyright_status"), 4L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/copyright_notice")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" name="app[copyright_notice]" placeholder="" value="${page.v(page.at("config.app.copyright_notice"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/browser_junmp")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="app[browser_junmp]" value="0" title="${page.lang().get("close")}" checked="${page.eq(page.at("config.app.browser_junmp"), 0L)}">
                        <input type="radio" name="app[browser_junmp]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.app.browser_junmp"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/browser_junmp_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/404")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[page_404]" placeholder="" value="${page.v(page.at("config.app.page_404"))}" class="layui-input w150">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/404_tip")}</div>
                </div>
            </div>

            <div class="layui-tab-item">
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/player_sort")}：</label>
                        <div class="layui-input-inline">
                            <input type="radio" name="app[player_sort]" value="0" title="${page.lang().get("add")}" checked="${page.eq(page.at("config.app.player_sort"), 0L)}">
                            <input type="radio" name="app[player_sort]" value="1" title="${page.lang().get("admin/system/config/global")}" checked="${page.eq(page.at("config.app.player_sort"), 1L)}">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/player_sort_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/encrypt")}：</label>
                        <div class="layui-input-inline">
                            <select style="width:150px;" name="app[encrypt]">
                                <option value="0">${page.lang().get("admin/system/config/encrypt_not")}</option>
                                <option value="1" selected="${page.eq(page.at("config.app.encrypt"), 1L)}">escape</option>
                                <option value="2" selected="${page.eq(page.at("config.app.encrypt"), 2L)}">base64</option>
                            </select>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/search_hot")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[search_hot]" placeholder="${page.lang().get("multi_separate_tip")}" value="${page.v(page.at("config.app.search_hot"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/art_extend_class")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[art_extend_class]" placeholder="" value="${page.v(page.at("config.app.art_extend_class"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_class")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_class]" placeholder="" value="${page.v(page.at("config.app.vod_extend_class"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_state")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_state]" placeholder="" value="${page.v(page.at("config.app.vod_extend_state"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_version")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_version]" placeholder="" value="${page.v(page.at("config.app.vod_extend_version"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_area")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_area]" placeholder="" value="${page.v(page.at("config.app.vod_extend_area"))}" class="layui-input">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_lang")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_lang]" placeholder="" value="${page.v(page.at("config.app.vod_extend_lang"))}" class="layui-input">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_year")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_year]" placeholder="" value="${page.v(page.at("config.app.vod_extend_year"))}" class="layui-input">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/vod_extend_weekday")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[vod_extend_weekday]" placeholder="" value="${page.v(page.at("config.app.vod_extend_weekday"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/actor_extend_area")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="app[actor_extend_area]" placeholder="" value="${page.v(page.at("config.app.actor_extend_area"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/filter_words")}：</label>
                        <div class="layui-input-block">
                            <textarea name="app[filter_words]" class="layui-textarea" placeholder="${page.lang().get("admin/system/config/filter_words_tip")}">${page.t(page.at("config.app.filter_words"))}</textarea>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/config/extra_var")}：</label>
                        <div class="layui-input-block">
                            <textarea name="app[extra_var]" class="layui-textarea" placeholder="${page.lang().get("admin/system/config/extra_var_tip")}">${page.t(page.at("config.app.extra_var"))}</textarea>
                        </div>
                    </div>
                </div>


            <div class="layui-tab-item">
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/collect_timespan")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="app[collect_timespan]" placeholder="" value="${page.v(page.at("config.app.collect_timespan"))}" class="layui-input w150">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/collect_timespan_tip")}</div>
                </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/pagesize")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="app[pagesize]" placeholder="${page.lang().get("admin/system/config/pagesize_tip")}" value="${page.v(page.at("config.app.pagesize"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/makesize")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="app[makesize]" placeholder="${page.lang().get("admin/system/config/makesize_tip")}" value="${page.v(page.at("config.app.makesize"))}" class="layui-input w150">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/admin_login_verify")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="app[admin_login_verify]" value="0" title="${page.lang().get("close")}" checked="${page.eq(page.at("config.app.admin_login_verify"), "0")}">
                        <input type="radio" name="app[admin_login_verify]" value="1" title="${page.lang().get("open")}" checked="${!page.eq(page.at("config.app.admin_login_verify"), "0")}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/editor")}：</label>
                    <div class="layui-input-inline">
                        <select style="width:150px;" name="app[editor]">
                            @for(var item_vo : page.each(page.at("editors.ext_list")))
                            <option value="${page.v(item_vo.key())}" selected="${page.eq(page.at("config.app.editor"), item_vo.key())}">${page.t(item_vo.value())}</option>
                            @endfor
                        </select>
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/config/editor_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/config/lang")}：</label>
                    <div class="layui-input-inline" >
                        <select class="w150" name="app[lang]">
                            @for(var item_vo : page.each(page.at("langs")))
                            <option value="${page.v(item_vo.value())}" selected="${page.eq(page.at("config.app.lang"), item_vo.value())}">${page.t(item_vo.value())}</option>
                            @endfor
                        </select>
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
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.cookie.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/system-config.js" data-upload-url="${page.url("upload/upload")}" data-test-cache-url="${page.url("system/test_cache")}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-wait="${page.lang().get("wait_submit")}" data-msg-test-err="${page.lang().get("admin/system/config/test_err")}"></script>
`)
