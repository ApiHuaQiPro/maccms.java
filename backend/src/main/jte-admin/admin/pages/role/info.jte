@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>
    
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="role_id" value="${page.info().s("role_id")}">

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
                            <select name="role_level">
                                <option value="0">${page.lang().get("select_level")}</option>
                                <option value="9" selected="${page.eq("role_level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                                <option value="1" selected="${page.eq("role_level", "1")}">${page.lang().get("level")}1</option>
                                <option value="2" selected="${page.eq("role_level", "2")}">${page.lang().get("level")}2</option>
                                <option value="3" selected="${page.eq("role_level", "3")}">${page.lang().get("level")}3</option>
                                <option value="4" selected="${page.eq("role_level", "4")}">${page.lang().get("level")}4</option>
                                <option value="5" selected="${page.eq("role_level", "5")}">${page.lang().get("level")}5</option>
                                <option value="6" selected="${page.eq("role_level", "6")}">${page.lang().get("level")}6</option>
                                <option value="7" selected="${page.eq("role_level", "7")}">${page.lang().get("level")}7</option>
                                <option value="8" selected="${page.eq("role_level", "8")}">${page.lang().get("level")}8</option>

                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                            <select name="role_status">
                                <option value="1">${page.lang().get("reviewed")}</option>
                                <option value="0" selected="${page.eq("role_status", "0")}">${page.lang().get("reviewed_not")}</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w150">
                        <select name="role_lock">
                            <option value="0">${page.lang().get("unlock")}</option>
                            <option value="1" selected="${page.eq("role_lock", "1")}">${page.lang().get("lock")}</option>
                        </select>
                    </div>

                    <div class="layui-input-inline">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("vod_name")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" class="layui-input" value="${page.xs("vodName")}" readonly="readonly" placeholder="" name="">
                        </div>
                        <label class="layui-form-label">${page.lang().get("vod_id")}：</label>
                        <div class="layui-input-inline w70">
                            <input type="text" class="layui-input" value="${page.info().s("role_rid")}" readonly="readonly" placeholder="" name="role_rid">
                        </div>
                        <label class="layui-form-label">${page.lang().get("vod")}${page.lang().get("type")}：</label>
                        <div class="layui-input-inline w70">
                            <input type="text" class="layui-input" value="${page.xs("vodTypeName")}" readonly="readonly" placeholder="" name="">
                        </div>
                    </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("role_name")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.info().s("role_name")}" placeholder="" name="role_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("actor_name")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.info().s("role_actor")}" placeholder="" name="role_actor">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sort")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.info().s("role_sort")}" placeholder="" name="role_sort">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.info().s("role_en")}" placeholder="" name="role_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input" value="${page.info().s("role_letter")}" placeholder="" name="role_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w200">
                        <input type="text" class="layui-input color" value="${page.info().s("role_color")}" placeholder="" name="role_color">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w400">
                            <input type="text" class="layui-input" value="${page.info().s("role_remarks")}" placeholder="" name="role_remarks">
                        </div>
                    </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic")}：</label>
                    <div class="layui-input-inline w400 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.info().s("role_pic")}" placeholder="" id="role_pic" name="role_pic">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="" id="upload1">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("content")}：</label>
                        <div class="layui-input-block">
                            <textarea id="role_content" name="role_content" type="text/plain" style="width:99%;height:300px">${page.contentImg(page.info().s("role_content"))}</textarea>
                        </div>
                    </div>
                    
        </div>


                    <div class="layui-tab-item">
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("up")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_up")}" placeholder="" id="role_up" name="role_up">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hate")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_down")}" placeholder="" id="role_down" name="role_down">
                            </div>
                            <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_hits")}" placeholder="" id="role_hits" name="role_hits">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_hits_month")}" placeholder="" id="role_hits_month" name="role_hits_month" >
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_hits_week")}" placeholder="" id="role_hits_week" name="role_hits_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input " value="${page.info().s("role_hits_day")}" placeholder="" id="role_hits_day" name="role_hits_day">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("score")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_score")}" placeholder="" id="role_score" name="role_score">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_score_all")}" placeholder="" id="role_score_all" name="role_score_all">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_score_num")}" placeholder="" id="role_score_num" name="role_score_num">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("tpl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_tpl")}" placeholder="" name="role_tpl">
                            </div>
                            <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("role_jumpurl")}" placeholder="" name="role_jumpurl">
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
<script type="text/javascript" src="${page.head().assetsPath()}/role-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=role"}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}"></script>
`)
