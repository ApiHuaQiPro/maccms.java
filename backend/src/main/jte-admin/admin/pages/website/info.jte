@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>
    
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="website_id" value="${page.info().s("website_id")}">

        <div class="layui-tab">
            <ul class="layui-tab-title ">
                <li class="layui-this">${page.lang().get("base_info")}</li>
                <li>${page.lang().get("other_info")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">
                    
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("param")}：</label>
                    <div class="layui-input-inline w150">
                            <select name="type_id" lay-filter="type_id">
                                <option value="">${page.lang().get("select_type")}</option>
                            @template.admin.partials.typeOptions(types = page.types(), mid = 11, selected = page.info().has("type_id") ? page.info().s("type_id") : null, form = true)
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="website_level">
                                <option value="0">${page.lang().get("select_level")}</option>
                                <option value="9" selected="${page.eq("website_level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                                <option value="1" selected="${page.eq("website_level", "1")}">${page.lang().get("level")}1</option>
                                <option value="2" selected="${page.eq("website_level", "2")}">${page.lang().get("level")}2</option>
                                <option value="3" selected="${page.eq("website_level", "3")}">${page.lang().get("level")}3</option>
                                <option value="4" selected="${page.eq("website_level", "4")}">${page.lang().get("level")}4</option>
                                <option value="5" selected="${page.eq("website_level", "5")}">${page.lang().get("level")}5</option>
                                <option value="6" selected="${page.eq("website_level", "6")}">${page.lang().get("level")}6</option>
                                <option value="7" selected="${page.eq("website_level", "7")}">${page.lang().get("level")}7</option>
                                <option value="8" selected="${page.eq("website_level", "8")}">${page.lang().get("level")}8</option>

                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="website_status">
                                <option value="1" >${page.lang().get("reviewed")}</option>
                                <option value="0" selected="${page.eq("website_status", "0")}">${page.lang().get("reviewed_not")}</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                        <select name="website_lock">
                            <option value="0">${page.lang().get("unlock")}</option>
                            <option value="1" selected="${page.eq("website_lock", "1")}">${page.lang().get("lock")}</option>
                        </select>
                    </div>

                    <div class="layui-input-inline">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("name")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.info().s("website_name")}" placeholder="" name="website_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sub")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.info().s("website_sub")}" placeholder="" name="website_sub">
                    </div>

                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.info().s("website_en")}" placeholder="" name="website_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input" value="${page.info().s("website_letter")}" placeholder="" name="website_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input color" value="${page.info().s("website_color")}" placeholder="" name="website_color">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("website_jumpurl")}" placeholder="" name="website_jumpurl">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("area")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("website_area")}" placeholder="" name="website_area">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("lang")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("website_lang")}" placeholder="" name="website_lang">
                        </div>
                    </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">TAG：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.info().s("website_tag")}" placeholder="" name="website_tag">
                    </div>
                    <div class="layui-input-inline w120">
                        <input type="checkbox" name="uptag" title="${page.lang().get("auto_make")}" value="1" class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("class")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("website_class")}" placeholder="" id="website_class" name="website_class">
                        </div>
                        <div class="layui-input-inline w500 website_class_label">

                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("website_remarks")}" placeholder="" name="website_remarks">
                        </div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">LOGO：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.info().s("website_logo")}" placeholder="" id="website_logo" name="website_logo">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'upload-thumb'}}" id="upload3">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic")}：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.info().s("website_pic")}" placeholder="" id="website_pic" name="website_pic">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:1,thumb_class:'upload-thumb'}}" id="upload1">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label ">${page.lang().get("pic_screenshot")}：</label>
                        <div class="layui-input-inline w400 ">
                            <div class="layui-btn-group">
                                <button type="button" class="layui-btn screenshot"><i class="layui-icon layui-icon-upload"></i> ${page.lang().get("upload_pic")}</button>
                            </div>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <div class="layui-input-block">
                            <textarea id="website_pic_screenshot" name="website_pic_screenshot" placeholder="${page.lang().get("screenshot_tip")}" type="text/plain" style="width:100%;height:150px;">${page.strCorrect(page.info().s("website_pic_screenshot"))}</textarea>
                            <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                                <legend>${page.lang().get("screenshot_preview")}</legend>
                            </fieldset>
                            <div class="screenshot_list">
                                @for(com.maccms.backend.admin.web.view.content.ContentFormView.Shot vo : page.shots(page.info().s("website_pic_screenshot")))
                                <div data-src="${vo.url()}"><a href="javascript:;" class="del_screenshot">${page.lang().get("del")}</a>
                                    <img src="${page.imgUrl(vo.url())}" alt="" class="layui-upload-img screenshot-img">
                                </div>
                                @endfor
                            </div>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("blurb")}：</label>
                        <div class="layui-input-block">
                            <textarea name="website_blurb" cols="" rows="3" class="layui-textarea"  placeholder="${page.lang().get("blurb_auto_tip")}" style="height:40px;">${page.info().s("website_blurb")}</textarea>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("content")}：</label>
                        <div class="layui-input-block">
                            <textarea id="website_content" name="website_content" type="text/plain" style="width:99%;height:300px">${page.contentImg(page.info().s("website_content"))}</textarea>
                        </div>
                    </div>
                    
        </div>


                    <div class="layui-tab-item">
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("up")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_up")}" placeholder="" id="website_up" name="website_up">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hate")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_down")}" placeholder="" id="website_down" name="website_down">
                            </div>
                            <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_hits")}" placeholder="" id="website_hits" name="website_hits">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_hits_month")}" placeholder="" id="website_hits_month" name="website_hits_month" >
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_hits_week")}" placeholder="" id="website_hits_week" name="website_hits_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input " value="${page.info().s("website_hits_day")}" placeholder="" id="website_hits_day" name="website_hits_day">
                            </div>
                        </div>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("score")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_score")}" placeholder="" id="website_score" name="website_score">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_score_all")}" placeholder="" id="website_score_all" name="website_score_all">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_score_num")}" placeholder="" id="website_score_num" name="website_score_num">
                            </div>
                        </div>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/website/referer")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_referer")}" placeholder="" id="website_referer" name="website_referer">
                            </div>
                            <label class="layui-form-label">${page.lang().get("admin/website/referer_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_referer_month")}" placeholder="" id="website_referer_month" name="website_referer_month">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("admin/website/referer_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_referer_week")}" placeholder="" id="website_referer_week" name="website_referer_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("admin/website/referer_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_referer_day")}" placeholder="" id="website_referer_day" name="website_referer_day">
                            </div>
                        </div>


                        <div class="layui-form-item">

                            <label class="layui-form-label">${page.lang().get("tpl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("website_tpl")}" placeholder="" name="website_tpl">
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
<script type="text/javascript" src="${page.head().assetsPath()}/website-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=website"}" data-shot-url="${page.url().build("upload/upload") + "?flag=website_screenshot"}" data-extend-url="${page.url().build("type/extend")}" data-annex-del-url="${page.url().build("annex/del")}" data-img-key="${page.img().imgKey()}" data-img-api="${page.img().imgApi()}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-msg-del="${page.lang().get("del")}" data-type-id="${page.editing("website_id") ? page.info().s("type_id") : ""}"></script>
`)
