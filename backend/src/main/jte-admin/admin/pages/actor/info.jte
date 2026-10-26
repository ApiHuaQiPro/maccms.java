@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="actor_id" value="${page.v("actor_id")}">

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
                            @template.admin.partials.typeOptions(types = page.types(), mid = 8, selected = page.info().has("type_id") ? page.info().s("type_id") : null, form = true)
                        </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="actor_level">
                                <option value="0">${page.lang().get("select_level")}</option>
                                <option value="9" selected="${page.eq("actor_level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                                <option value="1" selected="${page.eq("actor_level", "1")}">${page.lang().get("level")}1</option>
                                <option value="2" selected="${page.eq("actor_level", "2")}">${page.lang().get("level")}2</option>
                                <option value="3" selected="${page.eq("actor_level", "3")}">${page.lang().get("level")}3</option>
                                <option value="4" selected="${page.eq("actor_level", "4")}">${page.lang().get("level")}4</option>
                                <option value="5" selected="${page.eq("actor_level", "5")}">${page.lang().get("level")}5</option>
                                <option value="6" selected="${page.eq("actor_level", "6")}">${page.lang().get("level")}6</option>
                                <option value="7" selected="${page.eq("actor_level", "7")}">${page.lang().get("level")}7</option>
                                <option value="8" selected="${page.eq("actor_level", "8")}">${page.lang().get("level")}8</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="actor_status">
                                <option value="1">${page.lang().get("reviewed")}</option>
                                <option value="0" selected="${page.eq("actor_status", "0")}">${page.lang().get("reviewed_not")}</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                        <select name="actor_sex">
                            <option value="${page.lang().get("male")}">${page.lang().get("male")}</option>
                            <option value="${page.lang().get("female")}" selected="${page.eq("actor_sex", "女")}">${page.lang().get("female")}</option>
                        </select>
                    </div>
                    <div class="layui-input-inline w150">
                        <select name="actor_lock">
                            <option value="0">${page.lang().get("unlock")}</option>
                            <option value="1" selected="${page.eq("actor_lock", "1")}">${page.lang().get("lock")}</option>
                        </select>
                    </div>

                    <div class="layui-input-inline">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("actor_name")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.v("actor_name")}" placeholder="" name="actor_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("alias")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.v("actor_alias")}" placeholder="" name="actor_alias">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.v("actor_en")}" placeholder="" name="actor_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.v("actor_letter")}" placeholder="" name="actor_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input color" value="${page.v("actor_color")}" placeholder="" name="actor_color">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("blood")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_blood")}" placeholder="A,B,AB,O" name="actor_blood">
                        </div>
                        <label class="layui-form-label">${page.lang().get("area")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_area")}" placeholder="" name="actor_area">
                        </div>
                        <label class="layui-form-label">${page.lang().get("birtharea")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_birtharea")}" placeholder="" name="actor_birtharea">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("height")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_height")}" placeholder="cm" name="actor_height">
                        </div>
                        <label class="layui-form-label">${page.lang().get("weight")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_weight")}" placeholder="kg" name="actor_weight">
                        </div>
                        <label class="layui-form-label">${page.lang().get("birthday")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_birthday")}" placeholder="2000-01-01" name="actor_birthday">
                        </div>
                    </div>

                    <div class="layui-form-item">

                        <label class="layui-form-label">${page.lang().get("starsign")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_starsign")}" placeholder="" name="actor_starsign">
                        </div>
                        <label class="layui-form-label">${page.lang().get("school")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_school")}" placeholder="" name="actor_school">
                        </div>
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w200">
                            <input type="text" class="layui-input" value="${page.v("actor_remarks")}" placeholder="" name="actor_remarks" id="actor_remarks">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("works")}：</label>
                        <div class="layui-input-inline w800">
                            <input type="text" class="layui-input" value="${page.v("actor_works")}" placeholder="${page.lang().get("multi_separate_tip")}" name="actor_works">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">TAG：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("actor_tag")}" placeholder="" name="actor_tag">
                        </div>
                        <div class="layui-input-inline w120">
                            <input type="checkbox" name="uptag" title="${page.lang().get("auto_make")}" value="1" class="layui-checkbox checkbox-ids" lay-skin="primary">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("class")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v("actor_class")}" placeholder="" id="actor_class" name="actor_class">
                        </div>
                        <div class="layui-input-inline w500 actor_class_label">

                        </div>
                    </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic")}：</label>
                    <div class="layui-input-inline w400 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("actor_pic")}" placeholder="" id="actor_pic" name="actor_pic">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="" id="upload1">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("blurb")}：</label>
                        <div class="layui-input-block">
                            <textarea name="actor_blurb" cols="" rows="3" class="layui-textarea"  placeholder="${page.lang().get("blurb_auto_tip")}" style="height:40px;">${page.t("actor_blurb")}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("content")}：</label>
                        <div class="layui-input-block">
                            <textarea id="actor_content" name="actor_content" type="text/plain" style="width:99%;height:300px">${page.text(page.contentImg(page.info().s("actor_content")))}</textarea>
                        </div>
                    </div>

        </div>

                <div class="layui-tab-item">
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("up")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_up")}" placeholder="" id="actor_up" name="actor_up">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hate")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_down")}" placeholder="" id="actor_down" name="actor_down">
                            </div>
                            <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_hits")}" placeholder="" id="actor_hits" name="actor_hits">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_hits_month")}" placeholder="" id="actor_hits_month" name="actor_hits_month" >
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_hits_week")}" placeholder="" id="actor_hits_week" name="actor_hits_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input " value="${page.v("actor_hits_day")}" placeholder="" id="actor_hits_day" name="actor_hits_day">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("score")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_score")}" placeholder="" id="actor_score" name="actor_score">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_score_all")}" placeholder="" id="actor_score_all" name="actor_score_all">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_score_num")}" placeholder="" id="actor_score_num" name="actor_score_num">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("tpl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_tpl")}" placeholder="" name="actor_tpl">
                            </div>
                            <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.v("actor_jumpurl")}" placeholder="" name="actor_jumpurl">
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
<script type="text/javascript" src="${page.head().assetsPath()}/actor-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=actor"}" data-extend-url="${page.url().build("type/extend")}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-type-id="${page.editing("actor_id") ? page.info().s("type_id") : ""}"></script>
`)
