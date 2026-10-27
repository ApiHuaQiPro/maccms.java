@import com.maccms.backend.admin.web.view.collect.CjInfoView
@import com.maccms.backend.admin.web.view.AdminText
@import java.util.Map
@param CjInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <div class="layui-tab layui-tab-brief">
        <!--添加采集点 start-->
        <div class="layui-tab-content">
            <form class="layui-form" name="myform" method="post" id="myform">
                <input type="hidden" name="data[nodeid]" value="${page.v("nodeid")}">
                <div class="layui-tab layui-tab-card" style="min-height: 430px;">
                    <ul class="layui-tab-title">
                        <li class="layui-this">${page.lang().get("admin/cj/rule_url")}</li>
                        <li>${page.lang().get("admin/cj/rule_content")}</li>
                        <li>${page.lang().get("admin/cj/rule_diy")}</li>
                        <li>${page.lang().get("admin/cj/adv_config")}</li>
                    </ul>
                    <div class="layui-tab-content">
                        <!--网址规则 start-->
                        <div class="layui-tab-item layui-show">
                            <fieldset class="layui-elem-field layui-field-title" style="margin-top: 20px;">
                                <legend>${page.lang().get("base_info")}</legend>
                            </fieldset>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/rule_name")}：</label>
                                <div class="layui-input-block" style="width: 60%">
                                    <input type="text" name="data[name]" placeholder="" value="${page.v("name")}" class="layui-input">
                                </div>
                            </div>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/page_charset")}：</label>
                                <div class="layui-input-block">
                                    <input type="radio" name="data[sourcecharset]" value="GBK" title="GBK" checked="${page.is("sourcecharset", "GBK")}">
                                    <input type="radio" name="data[sourcecharset]" value="UTF-8" title="UTF-8" checked="${page.is("sourcecharset", "UTF-8")}">
                                    <input type="radio" name="data[sourcecharset]" value="BIG5" title="BIG5" checked="${page.is("sourcecharset", "BIG5")}">
                                </div>
                            </div>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/cj_model")}：</label>
                                <div class="layui-input-block">
                                    <input type="radio" name="data[mid]" value="1" title="${page.lang().get("vod")}" checked="${!page.eq("mid", 2)}">
                                    <input type="radio" name="data[mid]" value="2" title="${page.lang().get("art")}" checked="${page.eq("mid", 2)}">
                                </div>
                            </div>

                            <fieldset class="layui-elem-field layui-field-title" style="margin-top: 20px;">
                                <legend>${page.lang().get("admin/cj/url_collect")}</legend>
                            </fieldset>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/url_type")}：</label>
                                <div class="layui-input-block">
                                    <input type="radio" name="data[sourcetype]" id="_1" value="1" lay-filter="sourcetype" title="${page.lang().get("admin/cj/sequence_url")}" checked="${page.eq("sourcetype", 1)}">
                                    <input type="radio" name="data[sourcetype]" id="_2" value="2" lay-filter="sourcetype" title="${page.lang().get("admin/cj/multi_url")}" checked="${page.eq("sourcetype", 2)}">
                                    <input type="radio" name="data[sourcetype]" id="_3" value="3" lay-filter="sourcetype" title="${page.lang().get("admin/cj/one_url")}" checked="${page.eq("sourcetype", 3)}">
                                </div>
                            </div>
                            <div id="url_type_1" style="${!page.eq("sourcetype", 1) ? "display:none" : null}">
                                <div class="layui-form-item">
                                    <label class="layui-form-label">${page.lang().get("admin/cj/cj_url")}：</label>
                                    <div class="layui-input-inline" style="width: 60%;">
                                        <input type="text" name="urlpage1" id="urlpage_1" placeholder="http://..." value="${page.v("urlpage")}" class="layui-input">
                                        <div class="layui-form-mid layui-word-aux">
                                            (如：http://www.phpcms.cn/help/rumen/(*).html,${page.lang().get("use")}(*)${page.lang().get("admin/cj/wildcard_tip")}。
                                        </div>
                                    </div>
                                </div>
                                <div class="layui-form-item">
                                    <label class="layui-form-label">${page.lang().get("admin/cj/page_num_config")}：</label>
                                    <div class="layui-form-mid">${page.lang().get("start")}</div>
                                    <div class="layui-input-inline" style="width: 60px;">
                                        <input type="text" name="data[pagesize_start]" value="${page.v("pagesize_start")}" class="layui-input">
                                    </div>
                                    <div class="layui-form-mid"> ${page.lang().get("end")}</div>
                                    <div class="layui-input-inline" style="width: 60px;">
                                        <input type="text" name="data[pagesize_end]" value="${page.v("pagesize_end")}" class="layui-input">
                                    </div>
                                    <div class="layui-form-mid">${page.lang().get("admin/cj/page_num_increment")}</div>
                                    <div class="layui-input-inline" style="width: 60px;">
                                        <input type="text" name="data[par_num]" value="${page.v("par_num")}" class="layui-input">
                                    </div>
                                    <div class="layui-input-inline" style="width:10%;">
                                        <a class="layui-btn" data-cj="testUrl" href="javascript:;">${page.lang().get("test")}</a>
                                    </div>
                                </div>
                            </div>
                            <!--多个网址-->
                            <div id="url_type_2" class="layui-form-item" style="${!page.eq("sourcetype", 2) ? "display:none" : null}">
                                <label class="layui-form-label">${page.lang().get("admin/cj/cj_url")}：</label>
                                <div class="layui-input-inline" style="width: 60%;">
                                    <textarea class="layui-textarea" name="urlpage2" id="urlpage_2">${page.t("urlpage")}</textarea>
                                    <div class="layui-form-mid layui-word-aux">
                                        ${page.lang().get("admin/cj/one_per_line")}
                                    </div>
                                </div>
                            </div>
                            <!--单一网址-->
                            <div id="url_type_3" class="layui-form-item" style="${!page.eq("sourcetype", 3) ? "display:none" : null}">
                                <label class="layui-form-label">${page.lang().get("admin/cj/cj_url")}：</label>
                                <div class="layui-input-inline" style="width: 60%;">
                                    <input type="text" name="urlpage3" id="urlpage_3" placeholder="http://..." value="${page.v("urlpage")}" class="layui-input">
                                </div>
                            </div>

                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/url_config")}：</label>
                                <div class="layui-form-mid">${page.lang().get("admin/cj/url_must_contain")}</div>
                                <div class="layui-input-inline" style="width: 160px;">
                                    <input type="text" name="data[url_contain]" value="${page.v("url_contain")}" class="layui-input">
                                </div>
                                <div class="layui-form-mid"> ${page.lang().get("admin/cj/url_not_contain")}</div>
                                <div class="layui-input-inline" style="width: 160px;">
                                    <input type="text" name="data[url_except]" value="${page.v("url_except")}" class="layui-input">
                                </div>
                            </div>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/collect_interval")}：</label>
                                <div class="layui-input-inline">
                                    <textarea name="data[url_start]" class="layui-textarea">${page.t("url_start")}</textarea>
                                </div>
                                <div class="layui-form-mid">${page.lang().get("to")}</div>
                                <div class="layui-input-inline">
                                    <textarea name="data[url_end]" class="layui-textarea">${page.t("url_end")}</textarea>
                                </div>
                            </div>
                        </div>

                        <!--网址规则 end-->
                            <!--内容规则 start-->
                        <div class="layui-tab-item">
                            <blockquote class="layui-elem-quote layui-text" style="margin:20px 0;border-left-color: #ff5722;">
                                    ${AdminText.trusted(page.lang().get("admin/cj/wildcard_prompt"))}
                            </blockquote>
                            <div class="layui-btn-group">
                                    <a class="layui-btn" href="javascript:void(0);" data-cj="showAll">${page.lang().get("expand_all")}</a>
                                    <a class="layui-btn" href="javascript:void(0);" data-cj="hideAll">${page.lang().get("fold_all")}</a>
                            </div>
                            <div class="layui-collapse" lay-filter="lay_state" style="margin: 20px 0;">
                                    <div class="layui-colla-item">
                                        <h2 class="layui-colla-title">${page.lang().get("admin/cj/title_rule")}</h2>
                                        <div class="layui-colla-content layui-show">
                                            <div class="layui-form-item">
                                                <label class="layui-form-label">${page.lang().get("admin/cj/match_rule")}：</label>
                                                <div class="layui-input-inline w300">
                                                    <textarea name="data[title_rule]" id="title_rule" class="layui-textarea">${page.t("title_rule")}</textarea>
                                                    <div class="layui-form-mid layui-word-aux">
                                                        ${page.lang().get("use")}"<a href="javascript:;" data-insert-id="title_rule" data-insert-text="[内容]"> [内容] </a>"${page.lang().get("admin/cj/wildcard_tip")}
                                                    </div>
                                                </div>
                                                <div class="layui-form-mid">${page.lang().get("admin/cj/filter_rule")}：</div>
                                                <div class="layui-input-inline w300">
                                                    <textarea name="data[title_html_rule]" id="title_html_rule" class="layui-textarea">${page.t("title_html_rule")}</textarea>
                                                    <div class="layui-form-mid layui-word-aux">
                                                        <input type="button" value="${page.lang().get("select")}" class="layui-btn layui-btn-xs" data-add-tag="title_html_rule">
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    </div>

                                <div class="layui-colla-item">
                                    <h2 class="layui-colla-title">${page.lang().get("admin/cj/type_rule")}</h2>
                                    <div class="layui-colla-content layui-show">
                                        <div class="layui-form-item">
                                            <label class="layui-form-label">${page.lang().get("admin/cj/match_rule")}：</label>
                                            <div class="layui-input-inline w300">
                                                <textarea name="data[type_rule]" id="type_rule" class="layui-textarea">${page.t("type_rule")}</textarea>
                                                <div class="layui-form-mid layui-word-aux">
                                                    ${page.lang().get("use")}"<a href="javascript:;" data-insert-id="content_rule" data-insert-text="[内容]"> [内容] </a>"${page.lang().get("admin/cj/wildcard_tip")}
                                                </div>
                                            </div>
                                            <div class="layui-form-mid">${page.lang().get("admin/cj/filter_rule")}：</div>
                                            <div class="layui-input-inline w300">
                                                <textarea name="data[type_html_rule]" id="type_html_rule" class="layui-textarea">${page.t("type_html_rule")}</textarea>
                                                <div class="layui-form-mid layui-word-aux">
                                                    <input type="button" value="${page.lang().get("select")}" class="layui-btn layui-btn-xs" data-add-tag="type_html_rule">
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </div>

                                    <div class="layui-colla-item">
                                        <h2 class="layui-colla-title">${page.lang().get("admin/cj/content_rule")}</h2>
                                        <div class="layui-colla-content layui-show">
                                            <div class="layui-form-item">
                                                <label class="layui-form-label">${page.lang().get("admin/cj/match_rule")}：</label>
                                                <div class="layui-input-inline w300">
                                                    <textarea name="data[content_rule]" id="content_rule" class="layui-textarea">${page.t("content_rule")}</textarea>
                                                    <div class="layui-form-mid layui-word-aux">
                                                        ${page.lang().get("use")}"<a href="javascript:;" data-insert-id="content_rule" data-insert-text="[内容]"> [内容] </a>"${page.lang().get("admin/cj/wildcard_tip")}
                                                    </div>
                                                </div>
                                                <div class="layui-form-mid">${page.lang().get("admin/cj/filter_rule")}：</div>
                                                <div class="layui-input-inline w300">
                                                    <textarea name="data[content_html_rule]" id="content_html_rule" class="layui-textarea">${page.t("content_html_rule")}</textarea>
                                                    <div class="layui-form-mid layui-word-aux">
                                                        <input type="button" value="${page.lang().get("select")}" class="layui-btn layui-btn-xs" data-add-tag="content_html_rule">
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    </div>

                                    <div class="layui-colla-item">
                                        <h2 class="layui-colla-title">${page.lang().get("admin/cj/page_mode")}</h2>
                                        <div class="layui-colla-content layui-show">
                                            <div class="layui-form-item">
                                                <input type="radio" name="data[content_page_rule]" id="_1" value="1" title="${page.lang().get("admin/cj/list_all_mode")}" lay-filter="content_page_rule" checked="${!page.eq("content_page_rule", 2)}">
                                                <input type="radio" name="data[content_page_rule]" id="_2" value="2" title="${page.lang().get("admin/cj/next_page_mode")}" lay-filter="content_page_rule" checked="${page.eq("content_page_rule", 2)}">
                                            </div>

                                            <div class="layui-form-item" id="nextpage" style="${!page.eq("content_page_rule", 2) ? "display:none" : null}">
                                                <label class="layui-form-label">${page.lang().get("admin/cj/next_page_rule")}：</label>
                                                <div class="layui-input-inline w600">
                                                    <input type="text" name="data[content_nextpage]" class="layui-input" value="${page.v("content_nextpage")}">
                                                    <div class="layui-form-mid layui-word-aux">${AdminText.trusted(page.lang().get("admin/cj/next_page_tip"))}</div>
                                                </div>
                                            </div>
                                            <div class="layui-form-item">
                                                <label class="layui-form-label">${page.lang().get("admin/cj/match_rule")}：</label>
                                                从 <textarea rows="5" cols="40" name="data[content_page_start]" id="content_page_start">${page.t("content_page_start")}</textarea> ${page.lang().get("to")}
                                                <textarea rows="5" cols="40" name="data[content_page_end]" id="content_page_end">${page.t("content_page_end")}</textarea>
                                            </div>
                                        </div>
                                    </div>
                            </div>
                        </div>

                        <!--内容规则 end-->
                        <!--自定义规则 start-->
                        <div class="layui-tab-item" id="customize_config">

                            <div class="layui-form-item">
                                <div class="layui-input-block">
                                    <a class="layui-btn layui-btn-sm layui-btn-normal" href="javascript:;" data-cj="addCaiji">${page.lang().get("admin/cj/add_group")}</a>
                                </div>
                            </div>

                            @for(Map<String, String> vo : page.customize())
                            <div class="layui-form-item mt10">
                                <label class="layui-form-label">${page.lang().get("admin/cj/rule_name")}：</label>
                                <div class="layui-input-inline"><input type="text" name="data[customize_config][name][]" placeholder="" value="${page.diyAttr(vo, "name")}" class="layui-input"></div>
                                <div class="layui-form-mid">${page.lang().get("admin/cj/rule_name_en")}：</div>
                                <div class="layui-input-inline"><input type="text" name="data[customize_config][en_name][]" placeholder="" value="${page.diyAttr(vo, "en_name")}" class="layui-input"></div>
                            </div>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/match_rule")}：</label>
                                <div class="layui-input-inline">
                                    <textarea name="data[customize_config][rule][]" id="role_'+caiji+'" class="layui-textarea">${page.diyText(vo, "rule")}</textarea>
                                    <div class="layui-form-mid layui-word-aux">${page.lang().get("use")}"<a href="javascript:;" data-insert-id="title_rule" data-insert-text="[内容]"> [内容] </a>"${page.lang().get("admin/cj/wildcard_tip")}    </div>
                                </div>
                                <div class="layui-form-mid">${page.lang().get("admin/cj/filter_rule")}：</div>
                                <div class="layui-input-inline">
                                    <textarea name="data[customize_config][html_rule][]" id="content_html_rule_'+caiji+'" class="layui-textarea">${page.diyText(vo, "html_rule")}</textarea>
                                    <div class="layui-form-mid layui-word-aux"><a class="layui-btn layui-btn-xs" href="javascript:;" data-add-tag-caiji="content_html_rule_">${page.lang().get("select")}</a></div>
                                </div>
                            </div>
                            <hr>
                            @endfor

                        </div>
                        <!--自定义规则 end-->
                        <!--高级配置 start-->
                        <div class="layui-tab-item">

                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/content_page")}：</label>
                                <div class="layui-input-block">
                                    <input type="radio" name="data[content_page]" value="0" title="${page.lang().get("admin/cj/no_page")}">
                                    <div class="layui-unselect layui-form-radio layui-form-radioed">
                                        <i class="layui-anim layui-icon"></i>
                                        <div>${page.lang().get("admin/cj/no_page")}</div>
                                    </div>
                                    <input type="radio" name="data[content_page]" value="1" title="${page.lang().get("admin/cj/original_page")}" checked>
                                    <div class="layui-unselect layui-form-radio layui-form-radioed">
                                        <i class="layui-anim layui-icon"></i>
                                        <div>${page.lang().get("admin/cj/original_page")}</div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="layui-form-item">
                                <label class="layui-form-label">${page.lang().get("admin/cj/import_sort")}：</label>
                                <div class="layui-input-block">
                                    <input type="radio" name="data[coll_order]" value="1" title="${page.lang().get("admin/cj/same_to_site")}">
                                    <div class="layui-unselect layui-form-radio layui-form-radioed">
                                        <i class="layui-anim layui-icon"></i>
                                        <div>${page.lang().get("admin/cj/same_to_site")}</div>
                                    </div>
                                    <input type="radio" name="data[coll_order]" value="2" title="${page.lang().get("admin/cj/opposite_to_site")}" checked>
                                    <div class="layui-unselect layui-form-radio layui-form-radioed">
                                        <i class="layui-anim layui-icon"></i>
                                        <div>${page.lang().get("admin/cj/opposite_to_site")}</div>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <!--高级配置 end-->
                    </div>
                </div>
                <div class="layui-form-item">
                    <div class="layui-input-block w150" style="margin:20px auto;">
                        <button type="submit" name="dosubmit" id="dosubmit" class="layui-btn layui-btn-fluid">${page.lang().get("btn_save")}</button>
                    </div>
                </div>
            </form>
        </div>
        <!--添加采集点 end-->
    </div>
</div>
<style>
    .ib{display: inline-block;}
</style>
<div id="html_rule_show" class="aui_content" style="display:none; padding: 20px 25px;">
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_1" value="&lt;p([^&gt;]*)&gt;(.*)&lt;/p&gt;[|]"> &lt;p&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_2" value="&lt;a([^&gt;]*)&gt;(.*)&lt;/a&gt;[|]"> &lt;a&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_3" value="&lt;script([^&gt;]*)&gt;(.*)&lt;/script&gt;[|]"> &lt;script&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_4" value="&lt;iframe([^&gt;]*)&gt;(.*)&lt;/iframe&gt;[|]"> &lt;iframe&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_5" value="&lt;table([^&gt;]*)&gt;(.*)&lt;/table&gt;[|]"> &lt;table&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_6" value="&lt;span([^&gt;]*)&gt;(.*)&lt;/span&gt;[|]"> &lt;span&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_7" value="&lt;b([^&gt;]*)&gt;(.*)&lt;/b&gt;[|]"> &lt;b&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_8" value="&lt;img([^&gt;]*)&gt;[|]"> &lt;img&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_9" value="&lt;object([^&gt;]*)&gt;(.*)&lt;/object&gt;[|]"> &lt;object&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_10" value="&lt;embed([^&gt;]*)&gt;(.*)&lt;/embed&gt;[|]"> &lt;embed&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_11" value="&lt;param([^&gt;]*)&gt;(.*)&lt;/param&gt;[|]"> &lt;param&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_12" value="&lt;div([^&gt;]*)&gt;[|]"> &lt;div&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_13" value="&lt;/div&gt;[|]"> &lt;/div&gt;</label>
    <label class="ib" style="width:120px"><input type="checkbox" name="html_rule" id="_14" value="&lt;!--([^&gt;]*)--&gt;[|]"> &lt;!-- --&gt;</label>
    <br>
    <div class="bk15"></div>
    <center><input type="button" value="${page.lang().get("check_all")}" class="button" data-select-all="html_rule"> <input type="button" class="button" value="${page.lang().get("check_other")}" data-select-anti="html_rule"></center>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/cj-info.js" data-show-url="${page.showUrl()}" data-msg-filter-rule="${page.lang().get("admin/cj/filter_rule")}" data-msg-add="${page.lang().get("add")}" data-msg-cancel="${page.lang().get("cancel")}" data-msg-rule-name="${page.lang().get("rule_name")}" data-msg-rule-name-en="${page.lang().get("rule_name_en")}" data-msg-match-rule="${page.lang().get("admin/cj/match_rule")}" data-msg-use="${page.lang().get("use")}" data-msg-wildcard-tip="${page.lang().get("admin/cj/wildcard_tip")}" data-msg-select="${page.lang().get("select")}" data-msg-filter="${page.lang().get("admin/cj/filter_rule")}" data-msg-close="${page.lang().get("close")}"></script>
`)
