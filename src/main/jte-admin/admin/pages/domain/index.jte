@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="vod_id" value="${page.v(page.at("info.vod_id"))}">

        <div class="layui-tab">
            <ul class="layui-tab-title ">
                <li class="layui-this">${page.lang().get("admin/domain/title")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/domain/help_tip")}
                        <a class="layui-btn layui-btn-primary" href="${page.url("export")}" >${page.lang().get("export")}</a>
                        <a class="layui-btn layui-btn-primary layui-upload" data-href="${page.url("import")}" >${page.lang().get("import")}</a>
                    </blockquote>

                    <div id="domain_list" class="contents">
                        @for(var item_vo : page.each(page.at("domain_list")))
                        <div class="layui-form-item tr" data-i="${page.v(item_vo.key())}">
                        <label class="layui-form-label">${page.lang().get("website")}${page.t(page.at("n"))}：</label>
                            <div class="layui-input-inline w150"><input type="text" name="domain[site_url][]" class="layui-input" placeholder="${page.lang().get("domain")}" value="${page.v(page.at(item_vo.value(), "site_url"))}"></div>&nbsp;
                            <div class="layui-input-inline w150"><input type="text" name="domain[site_name][]" class="layui-input" placeholder="${page.lang().get("site_name")}" value="${page.v(page.at(item_vo.value(), "site_name"))}"></div>&nbsp;
                            <div class="layui-input-inline w150"><input type="text" name="domain[site_keywords][]" class="layui-input" placeholder="${page.lang().get("keywords")}" value="${page.v(page.at(item_vo.value(), "site_keywords"))}"></div>&nbsp;
                            <div class="layui-input-inline w150"><input type="text" name="domain[site_description][]" class="layui-input" placeholder="${page.lang().get("description")}" value="${page.v(page.at(item_vo.value(), "site_description"))}"></div>&nbsp;
                            <div class="layui-input-inline w150"><select name="domain[template_dir][]"><option value="no">${page.lang().get("select_template")}.</option>@for(var item_vo2 : page.each(page.at("templates")))<option value="${page.v(item_vo2.value())}" selected="${page.eq(item_vo2.value(), page.at(item_vo.value(), "template_dir"))}">${page.t(item_vo2.value())}</option>@endfor</select></div>
                            <div class="layui-input-inline w150"><input type="text" name="domain[html_dir][]" class="layui-input" placeholder="${page.lang().get("tpl_dir")}" value="${page.v(page.at(item_vo.value(), "html_dir"))}"></div>
                            <div class="layui-input-inline w150"><input type="text" name="domain[ads_dir][]" class="layui-input" placeholder="${page.lang().get("ads_dir")}" value="${page.v(page.at(item_vo.value(), "ads_dir"))}"></div>
                            <div> <a class="layui-badge-rim j-tr-del" data-href="${page.url("del?ids=" + page.s(page.at(item_vo.value(), "site_url")))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a></div>
                        </div>
                        @endfor
                    </div>
                    <div class="layui-form-item">
                        <label class=""><button class="layui-btn radius j-player-add" type="button">${page.lang().get("add_group")}</button></label>
                        <div class="layui-input-block">

                        </div>
                    </div>


        </div>

            </div>
        </div>

                <div class="layui-form-item center">
                    <div class="layui-input-block">

                        <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="">${page.lang().get("btn_save")}</button>
                        <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
                    </div>
                </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/domain-index.js" data-count="${page.count(page.at("domain_list"))}" data-import-url="${page.url("domain/import")}" data-templates="${page.json(page.at("templates"))}" data-lang-uploading="${page.lang().get("upload_ing")}" data-lang-website="${page.lang().get("website")}" data-lang-domain="${page.lang().get("domain")}" data-lang-site-name="${page.lang().get("site_name")}" data-lang-keywords="${page.lang().get("keywords")}" data-lang-description="${page.lang().get("description")}" data-lang-select-template="${page.lang().get("select_template")}" data-lang-tpl-dir="${page.lang().get("tpl_dir")}" data-lang-ads-dir="${page.lang().get("ads_dir")}" data-lang-del="${page.lang().get("del")}"></script>
`)
