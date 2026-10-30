@import com.maccms.backend.admin.web.view.content.ContentFormView
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="topic_id" value="${page.v("topic_id")}">

        <div class="layui-tab">
            <ul class="layui-tab-title ">
                <li class="layui-this">${page.lang().get("base_info")}</li>
                <li>${page.lang().get("other_info")}</li>
            </ul>
        <div class="layui-tab-content">

            <div class="layui-tab-item layui-show">

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("param")}：</label>
                    <div class="layui-input-inline ">
                        <select name="topic_status">
                            <option value="1" >${page.lang().get("reviewed")}</option>
                            <option value="0" selected="${page.eq("topic_status", "0")}">${page.lang().get("reviewed_not")}</option>
                        </select>
                    </div>
                    <div class="layui-input-inline ">
                        <select name="topic_level">
                            <option value="0">${page.lang().get("select_level")}</option>
                            <option value="9" selected="${page.eq("topic_level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                            <option value="1" selected="${page.eq("topic_level", "1")}">${page.lang().get("level")}1</option>
                            <option value="2" selected="${page.eq("topic_level", "2")}">${page.lang().get("level")}2</option>
                            <option value="3" selected="${page.eq("topic_level", "3")}">${page.lang().get("level")}3</option>
                            <option value="4" selected="${page.eq("topic_level", "4")}">${page.lang().get("level")}4</option>
                            <option value="5" selected="${page.eq("topic_level", "5")}">${page.lang().get("level")}5</option>
                            <option value="6" selected="${page.eq("topic_level", "6")}">${page.lang().get("level")}6</option>
                            <option value="7" selected="${page.eq("topic_level", "7")}">${page.lang().get("level")}7</option>
                            <option value="8" selected="${page.eq("topic_level", "8")}">${page.lang().get("level")}8</option>

                        </select>
                    </div>
                    <div class="layui-input-inline w110">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("name")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" lay-verify="topic_name" value="${page.v("topic_name")}" placeholder="" name="topic_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sub")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" class="layui-input" value="${page.v("topic_sub")}" placeholder="" name="topic_sub">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" lay-verify="topic_en" value="${page.v("topic_en")}" placeholder="" name="topic_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input" value="${page.v("topic_letter")}" placeholder="" name="topic_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input color" value="${page.v("topic_color")}" placeholder="" name="topic_color">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("topic_remarks")}" placeholder="" name="topic_remarks">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sort")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_sort")}" placeholder="" name="topic_sort">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("class")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("topic_type")}" placeholder="${page.lang().get("multi_separate_tip")}" name="topic_type">
                    </div>
                    <label class="layui-form-label">${page.lang().get("tpl")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" lay-verify="topic_tpl" value="${page.attr(page.def(page.info().s("topic_tpl"), "detail.html"))}" placeholder="" name="topic_tpl">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">TAG：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("topic_tag")}" placeholder="${page.lang().get("multi_separate_tip")}" name="topic_tag">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/topic/vod_include")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("topic_rel_vod")}" placeholder="${page.lang().get("vod")}id,${page.lang().get("multi_separate_tip")}" name="topic_rel_vod">
                    </div>
                    <div class="layui-input-inline">
                        <a class="layui-btn j-iframe" data-href="${page.url().build("vod/data") + "?select=1&input=topic_rel_vod"}" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/topic/art_include")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.v("topic_rel_art")}" placeholder="${page.lang().get("art")}id,${page.lang().get("multi_separate_tip")}" name="topic_rel_art">
                    </div>
                    <div class="layui-input-inline">
                        <a class="layui-btn j-iframe" data-href="${page.url().build("art/data") + "?select=1&input=topic_rel_art"}" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic")}：</label>
                    <div class="layui-input-inline w500 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("topic_pic")}" placeholder="" id="topic_pic" name="topic_pic">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:1,thumb_class:'upload-thumb'}}" id="upload1">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic_thumb")}：</label>
                    <div class="layui-input-inline w500 upload">
                        <input type="text" class="layui-input upload-input upload-thumb" style="max-width:100%;" value="${page.v("topic_pic_thumb")}" placeholder="" id="topic_pic_thumb" name="topic_pic_thumb">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:''}}" id="upload2">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("slide")}：</label>
                    <div class="layui-input-inline w500 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.v("topic_pic_slide")}" placeholder="" id="topic_pic_slide" name="topic_pic_slide">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:''}}" id="upload3">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("blurb")}：</label>
                    <div class="layui-input-block">
                        <textarea name="topic_blurb" cols="" rows="3" class="layui-textarea"  placeholder="${page.lang().get("blurb_auto_tip")}" style="height:40px;">${page.t("topic_blurb")}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("content")}：</label>
                    <div class="layui-input-block">
                        <textarea id="topic_content" name="topic_content" type="text/plain" style="width:99%;height:300px">${page.text(page.contentImg(page.info().s("topic_content")))}</textarea>
                    </div>
                </div>
            </div>

            <div class="layui-tab-item">

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("seo_key")}：</label>
                    <div class="layui-input-block w500">
                        <input type="text" class="layui-input" value="${page.v("topic_key")}" placeholder="" name="topic_key">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("seo_des")}：</label>
                    <div class="layui-input-block w500">
                        <input type="text" class="layui-input" value="${page.v("topic_des")}" placeholder="" name="topic_des">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("seo_title")}：</label>
                    <div class="layui-input-block w500">
                        <input type="text" class="layui-input" value="${page.v("topic_title")}" placeholder="" name="topic_title">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("up")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_up")}" placeholder="" id="topic_up" name="topic_up">
                    </div>
                    <label class="layui-form-label">${page.lang().get("hate")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_down")}" placeholder="" id="topic_down" name="topic_down">
                    </div>
                    <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("hits")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_hits")}" placeholder="" id="topic_hits" name="topic_hits">
                    </div>
                    <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_hits_month")}" placeholder="" id="topic_hits_month" name="topic_hits_month" >
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_hits_week")}" placeholder="" id="topic_hits_week" name="topic_hits_week">
                    </div>
                    <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input " value="${page.v("topic_hits_day")}" placeholder="" id="topic_hits_day" name="topic_hits_day">
                    </div>
                </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("score")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_score")}" placeholder="" id="topic_score" name="topic_score">
                    </div>
                    <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_score_all")}" placeholder="" id="topic_score_all" name="topic_score_all">
                    </div>
                    <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.v("topic_score_num")}" placeholder="" id="topic_score_num" name="topic_score_num">
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
<script type="text/javascript" src="${page.head().assetsPath()}/topic-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=topic"}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-msg-tpl="${page.lang().get("admin/topic/tpl_empty")}"></script>
`)
