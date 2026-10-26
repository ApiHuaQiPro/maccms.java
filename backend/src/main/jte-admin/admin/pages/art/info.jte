@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="art_id" value="${page.v("art_id")}">

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
                            @template.admin.partials.typeOptions(types = page.types(), mid = 2, selected = page.info().has("type_id") ? page.info().s("type_id") : null, form = true)
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="art_level">
                                <option value="0">${page.lang().get("select_level")}</option>
                                <option value="9" selected="${page.eq("art_level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                                <option value="1" selected="${page.eq("art_level", "1")}">${page.lang().get("level")}1</option>
                                <option value="2" selected="${page.eq("art_level", "2")}">${page.lang().get("level")}2</option>
                                <option value="3" selected="${page.eq("art_level", "3")}">${page.lang().get("level")}3</option>
                                <option value="4" selected="${page.eq("art_level", "4")}">${page.lang().get("level")}4</option>
                                <option value="5" selected="${page.eq("art_level", "5")}">${page.lang().get("level")}5</option>
                                <option value="6" selected="${page.eq("art_level", "6")}">${page.lang().get("level")}6</option>
                                <option value="7" selected="${page.eq("art_level", "7")}">${page.lang().get("level")}7</option>
                                <option value="8" selected="${page.eq("art_level", "8")}">${page.lang().get("level")}8</option>

                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="art_status">
                                <option value="1" >${page.lang().get("reviewed")}</option>
                                <option value="0" selected="${page.eq("art_status", "0")}">${page.lang().get("reviewed_not")}</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                        <select name="art_lock">
                            <option value="0">${page.lang().get("unlock")}</option>
                            <option value="1" selected="${page.eq("art_lock", "1")}">${page.lang().get("lock")}</option>
                        </select>
                    </div>

                    <div class="layui-input-inline">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("name")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("art_name")}" placeholder="" name="art_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sub")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("art_sub")}" placeholder="" name="art_sub">
                    </div>

                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("art_en")}" placeholder="" name="art_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input" value="${page.v("art_letter")}" placeholder="" name="art_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input color" value="${page.v("art_color")}" placeholder="" name="art_color">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">TAG：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("art_tag")}" placeholder="" name="art_tag">
                    </div>
                    <div class="layui-input-inline w120">
                        <input type="checkbox" name="uptag" title="${page.lang().get("auto_make")}" value="1" class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("art_remarks")}" placeholder="" name="art_remarks">
                        </div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("rel_vod")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("art_rel_vod")}" placeholder="${page.lang().get("admin/vod/rel_vod_tip")}" name="art_rel_vod">
                        </div>
                        <div class="layui-input-inline ">
                            <a class="layui-btn j-iframe" data-href="${page.url().build("vod/data")}?select=1&input=art_rel_vod" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("rel_art")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("art_rel_art")}" placeholder="${page.lang().get("admin/vod/rel_art_tip")}" name="art_rel_art">
                        </div>
                        <div class="layui-input-inline ">
                            <a class="layui-btn j-iframe" data-href="${page.url().build("art/data")}?select=1&input=art_rel_art" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("class")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("art_class")}" placeholder="" id="art_class" name="art_class">
                        </div>
                        <div class="layui-input-inline w500 art_class_label">

                        </div>
                    </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic")}：</label>
                    <div class="layui-input-inline w500 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("art_pic")}" placeholder="" id="art_pic" name="art_pic">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:1,thumb_class:'upload-thumb'}}" id="upload1">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic_thumb")}：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("art_pic_thumb")}" placeholder="" id="art_pic_thumb" name="art_pic_thumb">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'upload-thumb'}}" id="upload2">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic_slide")}：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("art_pic_slide")}" placeholder="" id="art_pic_slide" name="art_pic_slide">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'upload-thumb'}}" id="upload3">${page.lang().get("upload_pic")}</button>
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
                            <textarea id="art_pic_screenshot" name="art_pic_screenshot" placeholder="${page.lang().get("screenshot_tip")}" type="text/plain" style="width:100%;height:150px;">${page.text(page.strCorrect(page.info().s("art_pic_screenshot")))}</textarea>
                            <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                                <legend>${page.lang().get("screenshot_preview")}</legend>
                            </fieldset>
                            <div class="screenshot_list">
                                @for(com.maccms.backend.admin.web.view.content.ContentFormView.Shot vo : page.shots(page.info().s("art_pic_screenshot")))
                                <div data-src="${page.attr(vo.url())}"><a href="javascript:;" class="del_screenshot">${page.lang().get("del")}</a>
                                    <img src="${page.attr(page.imgUrl(vo.url()))}" alt="" class="layui-upload-img screenshot-img">
                                </div>
                                @endfor
                            </div>
                        </div>
                    </div>
                <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("blurb")}：</label>
                    <div class="layui-input-block">
                        <textarea name="art_blurb" cols="" rows="3" class="layui-textarea"  placeholder="${page.lang().get("blurb_auto_tip")}" style="height:40px;">${page.t("art_blurb")}</textarea>
                    </div>
                </div>

                    <div id="artlist" class="contents">
                    @for(com.maccms.backend.admin.web.view.content.ContentFormView.ArtPage vo : page.artPages())
                    <div class="layui-form-item" data-i="${vo.page()}">
                        <label class="layui-form-label">${page.lang().get("page_content")}${vo.page()}：</label>
                        <div class="layui-input-inline w200"><input type="text" name="art_title[]" class="layui-input" value="${page.attr(vo.title())}" placeholder="${page.lang().get("page_title")}"></div>
                        <div class="layui-input-inline w200"><input type="text" name="art_note[]" class="layui-input" value="${page.attr(vo.note())}" placeholder="${page.lang().get("page_note")}"></div>
                        <div class="layui-input-inline w200"><a href="javascript:void(0)" class="j-editor-clear">${page.lang().get("clear")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">${page.lang().get("del")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">上移</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">下移</a>&nbsp;<br></div>
                        <div class="p10 m20"></div>
                        <div class="layui-input-block"><textarea id="art_content${vo.page()}" name="art_content[]" type="text/plain" style="width:99%;height:250px">${page.text(page.contentImg(vo.content()))}</textarea></div>
                        </div>
                    @endfor
                </div>


                    <div class="layui-form-item">
                        <label class=""><button class="layui-btn radius j-editor-add" type="button">${page.lang().get("page_add")}</button></label>
                        <div class="layui-input-block">

                        </div>
                    </div>

        </div>

                <div class="layui-tab-item">
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("up")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_up")}" placeholder="" id="art_up" name="art_up">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hate")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_down")}" placeholder="" id="art_down" name="art_down">
                            </div>
                            <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_hits")}" placeholder="" id="art_hits" name="art_hits">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_hits_month")}" placeholder="" id="art_hits_month" name="art_hits_month" >
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_hits_week")}" placeholder="" id="art_hits_week" name="art_hits_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input " value="${page.v("art_hits_day")}" placeholder="" id="art_hits_day" name="art_hits_day">
                            </div>
                        </div>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("score")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_score")}" placeholder="" id="art_score" name="art_score">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_score_all")}" placeholder="" id="art_score_all" name="art_score_all">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_score_num")}" placeholder="" id="art_score_num" name="art_score_num">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("points_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_points")}" placeholder="" name="art_points">
                            </div>
                            <label class="layui-form-label">${page.lang().get("points_detail")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_points_detail")}" placeholder="" name="art_points_detail">
                            </div>
                            <label class="layui-form-label">${page.lang().get("tpl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_tpl")}" placeholder="" name="art_tpl">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("author")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_author")}" placeholder="" name="art_author">
                            </div>
                            <label class="layui-form-label">${page.lang().get("from")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_from")}" placeholder="" name="art_from">
                            </div>
                            <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_jumpurl")}" placeholder="" name="art_jumpurl">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("access_pwd")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_pwd")}" placeholder="${page.lang().get("not_static_is_ok")}" name="art_pwd">
                            </div>
                            <label class="layui-form-label">${page.lang().get("pwd_url")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("art_pwd_url")}" placeholder="" name="art_pwd_url">
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
<script type="text/javascript" src="${page.head().assetsPath()}/art-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=art"}" data-shot-url="${page.url().build("upload/upload") + "?flag=art_screenshot"}" data-extend-url="${page.url().build("type/extend")}" data-annex-del-url="${page.url().build("annex/del")}" data-img-key="${page.img().imgKey()}" data-img-api="${page.img().imgApi()}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-msg-del="${page.lang().get("del")}" data-pages="${page.artPages().size()}" data-msg-page-content="${page.lang().get("page_content")}" data-msg-page-title="${page.lang().get("page_title")}" data-msg-page-note="${page.lang().get("页备注")}" data-msg-clear="${page.lang().get("clear")}" data-type-id="${page.editing("art_id") ? page.info().s("type_id") : ""}"></script>
`)
