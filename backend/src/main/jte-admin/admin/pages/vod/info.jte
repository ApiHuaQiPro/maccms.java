@import com.maccms.backend.admin.web.view.content.ContentFormView
@import com.maccms.backend.admin.web.view.content.ViewEq
@import com.maccms.backend.content.application.LegacyMultiValue.PlaySource
@import java.util.List
@import java.util.Map
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>
    
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="vod_id" value="${page.info().s("vod_id")}">

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
                                @template.admin.partials.typeOptions(types = page.types(), mid = 1, selected = page.info().has("type_id") ? page.info().s("type_id") : null, form = true)
                            </select>
                    </div>

                    <div class="layui-input-inline w150">
                            <select name="vod_level">
                                <option value="0">${page.lang().get("select_level")}</option>
                                <option value="9" selected="${page.is("vod_level", 9)}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                                <option value="1" selected="${page.is("vod_level", 1)}">${page.lang().get("level")}1</option>
                                <option value="2" selected="${page.is("vod_level", 2)}">${page.lang().get("level")}2</option>
                                <option value="3" selected="${page.is("vod_level", 3)}">${page.lang().get("level")}3</option>
                                <option value="4" selected="${page.is("vod_level", 4)}">${page.lang().get("level")}4</option>
                                <option value="5" selected="${page.is("vod_level", 5)}">${page.lang().get("level")}5</option>
                                <option value="6" selected="${page.is("vod_level", 6)}">${page.lang().get("level")}6</option>
                                <option value="7" selected="${page.is("vod_level", 7)}">${page.lang().get("level")}7</option>
                                <option value="8" selected="${page.is("vod_level", 8)}">${page.lang().get("level")}8</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w120">
                            <select name="vod_status">
                                <option value="1" >${page.lang().get("reviewed")}</option>
                                <option value="0" selected="${page.eq("vod_status", "0")}">${page.lang().get("reviewed_not")}</option>
                            </select>
                    </div>
                    <div class="layui-input-inline w120">
                        <select name="vod_lock">
                            <option value="0">${page.lang().get("unlock")}</option>
                            <option value="1" selected="${page.is("vod_lock", 1)}">${page.lang().get("lock")}</option>
                        </select>
                    </div>
                    <div class="layui-input-inline w120">
                        <select name="vod_isend">
                            <option value="1" selected="${page.is("vod_isend", 1)}">${page.lang().get("admin/vod/is_end")}</option>
                            <option value="0" selected="${page.is("vod_isend", 0)}">${page.lang().get("admin/vod/no_end")}</option>
                        </select>
                    </div>
                    <div class="layui-input-inline w120">
                        <select name="vod_copyright">
                            <option value="0" selected="${page.eq("vod_copyright", "0")}">${page.lang().get("admin/vod/copyright_close")}</option>
                            <option value="1" selected="${page.eq("vod_copyright", "1")}">${page.lang().get("admin/vod/copyright_open")}</option>
                        </select>
                    </div>
                    <div class="layui-input-inline w110">
                        <input type="checkbox" name="uptime" title="${page.lang().get("update_time")}" value="1" checked class="layui-checkbox checkbox-ids" lay-skin="primary">
                    </div>
                </div>

                <div class="layui-form-item ">
                    <label class="layui-form-label">${page.lang().get("name")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.info().s("vod_name")}" placeholder="" name="vod_name" id="vod_name">
                    </div>
                    <label class="layui-form-label">${page.lang().get("sub")}：</label>
                    <div class="layui-input-inline ">
                        <input type="text" class="layui-input" value="${page.info().s("vod_sub")}" placeholder="" name="vod_sub" id="vod_sub">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("en")}：</label>
                    <div class="layui-input-inline w500">
                        <input type="text" class="layui-input" value="${page.info().s("vod_en")}" placeholder="" name="vod_en">
                    </div>
                    <label class="layui-form-label">${page.lang().get("letter")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input" value="${page.info().s("vod_letter")}" placeholder="" name="vod_letter">
                    </div>
                    <label class="layui-form-label">${page.lang().get("color")}：</label>
                    <div class="layui-input-inline w70">
                        <input type="text" class="layui-input color" value="${page.info().s("vod_color")}" placeholder="" name="vod_color">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">TAG：</label>
                        <div class="layui-input-inline w500  ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_tag")}" placeholder=""  name="vod_tag" id="vod_tag">
                        </div>
                        <div class="layui-input-inline w120">
                            <input type="checkbox" name="uptag" title="${page.lang().get("auto_make")}" value="1" class="layui-checkbox checkbox-ids" lay-skin="primary">
                        </div>


                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_remarks")}" placeholder="" name="vod_remarks" id="vod_remarks">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/move_behind")}：</label>
                        <div class="layui-input-inline ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_behind")}" placeholder="" name="vod_behind" id="vod_behind">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/vod/total")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_total")}" placeholder="" name="vod_total" id="vod_total">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/serial")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_serial")}" placeholder="" name="vod_serial" id="vod_serial">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/pubdate")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_pubdate")}" placeholder="" name="vod_pubdate" id="vod_pubdate">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("actor")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_actor")}" placeholder="" name="vod_actor" id="vod_actor">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/director")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_director")}" placeholder="" name="vod_director" id="vod_director">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/writer")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_writer")}" placeholder="" name="vod_writer" id="vod_writer">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/vod/tv")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_tv")}" placeholder="" name="vod_tv">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/weekday")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_weekday")}" placeholder="" name="vod_weekday">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/duration")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_duration")}" placeholder="" name="vod_duration" id="vod_duration">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/vod/douban_score")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_douban_score")}" placeholder="" name="vod_douban_score" id="vod_douban_score">
                        </div>
                        <label class="layui-form-label">${page.lang().get("admin/vod/douban_id")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" class="layui-input" value="${page.info().s("vod_douban_id")}" placeholder="" name="vod_douban_id" id="vod_douban_id">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn" id="btn_douban">${page.lang().get("search_data")}</button>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("rel_vod")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_rel_vod")}" placeholder="${page.lang().get("admin/vod/rel_vod_tip")}" name="vod_rel_vod">
                        </div>
                        <div class="layui-input-inline ">
                            <a class="layui-btn j-iframe" data-href="${page.url().build("vod/data")}?select=1&input=vod_rel_vod" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("rel_art")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_rel_art")}" placeholder="${page.lang().get("admin/vod/rel_art_tip")}" name="vod_rel_art">
                        </div>
                        <div class="layui-input-inline ">
                            <a class="layui-btn j-iframe" data-href="${page.url().build("art/data")}?select=1&input=vod_rel_art" href="javascript:;" title="${page.lang().get("search_data")}">${page.lang().get("search_data")}</a>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("class")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_class")}" placeholder="" id="vod_class" name="vod_class">
                        </div>
                        <div class="layui-input-inline w500 vod_class_label">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("years")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_year")}" placeholder="" id="vod_year" name="vod_year">
                        </div>
                        <div class="layui-input-inline w500 vod_year_label">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("area")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_area")}" placeholder="" id="vod_area" name="vod_area">
                        </div>
                        <div class="layui-input-inline w500 vod_area_label">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("lang")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_lang")}" placeholder="" id="vod_lang" name="vod_lang">
                        </div>
                        <div class="layui-input-inline w500 vod_lang_label">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/vod/version")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_version")}" placeholder="" id="vod_version" name="vod_version">
                        </div>
                        <div class="layui-input-inline w500 vod_version_label">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/vod/state")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.info().s("vod_state")}" placeholder="" id="vod_state" name="vod_state">
                        </div>
                        <div class="layui-input-inline w500 vod_state_label">

                        </div>
                    </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("pic")}：</label>
                    <div class="layui-input-inline w500 upload">
                        <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.info().s("vod_pic")}" placeholder="" id="vod_pic" name="vod_pic">
                    </div>
                    <div class="layui-input-inline ">
                        <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:1,thumb_class:'upload-thumb'}}" id="upload1">${page.lang().get("upload_pic")}</button>
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic_thumb")}：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input upload-thumb" style="max-width:100%;" value="${page.info().s("vod_pic_thumb")}" placeholder="" id="vod_pic_thumb" name="vod_pic_thumb">
                        </div>
                        <div class="layui-input-inline ">
                            <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'upload-thumb'}}" id="upload2">${page.lang().get("upload_pic")}</button>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic_slide")}：</label>
                        <div class="layui-input-inline w500 upload">
                            <input type="text" class="layui-input upload-input" style="max-width:100%;" value="${page.info().s("vod_pic_slide")}" placeholder="" id="vod_pic_slide" name="vod_pic_slide">
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
                            <textarea id="vod_pic_screenshot" name="vod_pic_screenshot" placeholder="${page.lang().get("screenshot_tip")}" type="text/plain" style="width:100%;height:150px;">${page.strCorrect(page.info().s("vod_pic_screenshot"))}</textarea>
                            <fieldset class="layui-elem-field layui-field-title" style="margin-top: 30px;">
                                <legend>${page.lang().get("screenshot_preview")}</legend>
                            </fieldset>
                            <div class="screenshot_list">
                                @for(ContentFormView.Shot vo : page.shots(page.info().s("vod_pic_screenshot")))
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
                        <textarea name="vod_blurb" cols="" rows="3" class="layui-textarea"  placeholder="${page.lang().get("blurb_auto_tip")}" style="height:40px;">${page.info().s("vod_blurb")}</textarea>
                    </div>
                </div>


                    <div id="player_list" class="contents">
                        @for(PlaySource vo : page.<java.util.List<com.maccms.backend.content.application.LegacyMultiValue.PlaySource>>x("playList"))
                        <div class="layui-form-item" data-i="${vo.sid()}">
                        <label class="layui-form-label">${page.lang().get("play")}${vo.sid()}：</label>
                            <div class="layui-input-inline w150"><select name="vod_play_from[]"><option value="no">${page.lang().get("admin/vod/select_player")}.</option>@for(Map<String, String> vo1 : page.<java.util.List<java.util.Map<String, String>>>x("playerList"))<option value="${vo1.get("from")}" selected="${ViewEq.eq(vo1.get("from"), vo.from())}">${vo1.get("show")}</option>@endfor</select></div>
                            <div class="layui-input-inline w150"><select name="vod_play_server[]"><option value="no">${page.lang().get("admin/vod/select_server")}.</option>@for(Map<String, String> vo2 : page.<java.util.List<java.util.Map<String, String>>>x("serverList"))<option value="${vo2.get("from")}" selected="${ViewEq.eq(vo2.get("from"), vo.server())}">${vo2.get("show")}</option>@endfor</select></div>
                            <div class="layui-input-inline w150"><input type="text" name="vod_play_note[]" class="layui-input" value="${vo.note()}" placeholder="${page.lang().get("remarks")}"></div>
                            <div class="layui-input-inline w400 p10"><a href="javascript:void(0)" class="j-editor-clear">${page.lang().get("clear")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">${page.lang().get("del")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">${page.lang().get("admin/vod/move_up")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">${page.lang().get("admin/vod/move_down")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-xz">${page.lang().get("admin/vod/correct")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-order">${page.lang().get("admin/vod/reverse_order")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-dn">${page.lang().get("admin/vod/del_prefix")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-upload">${page.lang().get("upload")}</a><br></div>
                            <div class="p10 m20"> </div>
                            <div class="layui-input-block "><textarea id="vod_play_url${vo.sid()}" name="vod_play_url[]" type="text/plain" style="width:100%;height:150px">${page.strCorrect(vo.url())}</textarea></div>
                        </div>
                        @endfor
                    </div>
                    <div class="layui-form-item">
                        <label class=""><button class="layui-btn radius j-player-add" type="button">${page.lang().get("admin/vod/add_group_play")}</button></label>
                        <div class="layui-input-block">

                        </div>
                    </div>

                    <hr class="layui-bg-gray">


                    <div id="downer_list" class="contents">
                        @for(PlaySource vo : page.<java.util.List<com.maccms.backend.content.application.LegacyMultiValue.PlaySource>>x("downList"))
                        <div class="layui-form-item" data-i="${vo.sid()}">
                            <label class="layui-form-label">${page.lang().get("down")}${vo.sid()}：</label>
                            <div class="layui-input-inline w150"><select name="vod_down_from[]"><option value="no">${page.lang().get("admin/vod/select_downer")}.</option>@for(Map<String, String> vo1 : page.<java.util.List<java.util.Map<String, String>>>x("downerList"))<option value="${vo1.get("from")}" selected="${ViewEq.eq(vo1.get("from"), vo.from())}">${vo1.get("show")}</option>@endfor</select></div>
                            <div class="layui-input-inline w150"><select name="vod_down_server[]"><option value="no">${page.lang().get("admin/vod/select_server")}.</option>@for(Map<String, String> vo2 : page.<java.util.List<java.util.Map<String, String>>>x("serverList"))<option value="${vo2.get("from")}" selected="${ViewEq.eq(vo2.get("from"), vo.server())}">${vo2.get("show")}</option>@endfor</select></div>
                            <div class="layui-input-inline w150"><input type="text" name="vod_down_note[]" class="layui-input" value="${vo.note()}" placeholder="${page.lang().get("remarks")}"></div>
                            <div class="layui-input-inline w400 p10"><a href="javascript:void(0)" class="j-editor-clear">${page.lang().get("clear")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">${page.lang().get("del")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">${page.lang().get("admin/vod/move_up")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">${page.lang().get("admin/vod/move_down")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-xz">${page.lang().get("admin/vod/correct")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-order">${page.lang().get("admin/vod/reverse_order")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-dn">${page.lang().get("admin/vod/del_prefix")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-upload">${page.lang().get("upload")}</a><br></div>
                            <div class="p10"> </div>
                            <div class="layui-input-block"><textarea id="vod_down_url${vo.sid()}" name="vod_down_url[]" type="text/plain" style="width:100%;height:150px">${page.strCorrect(vo.url())}</textarea></div>
                        </div>
                        @endfor
                    </div>
                    <div class="layui-form-item">
                        <label class=""><button class="layui-btn radius j-downer-add" type="button">${page.lang().get("admin/vod/add_group_down")}</button></label>
                        <div class="layui-input-block">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("content")}：</label>
                        <div class="layui-input-block">
                            <textarea id="vod_content" name="vod_content" type="text/plain" style="width:99%;height:250px">${page.contentImg(page.info().s("vod_content"))}</textarea>
                        </div>
                    </div>
        </div>

                <div class="layui-tab-item">
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("up")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_up")}" placeholder="" id="vod_up" name="vod_up">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hate")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_down")}" placeholder="" id="vod_down" name="vod_down">
                            </div>
                            <button class="layui-btn" type="button" id="btn_rnd">${page.lang().get("rnd_make")}</button>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_hits")}" placeholder="" id="vod_hits" name="vod_hits">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_month")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_hits_month")}" placeholder="" id="vod_hits_month" name="vod_hits_month" >
                            </div>
                            <label class="layui-form-label">${page.lang().get("author")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_author")}" placeholder="" name="vod_author" id="vod_author">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("hits_week")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_hits_week")}" placeholder="" id="vod_hits_week" name="vod_hits_week">
                            </div>
                            <label class="layui-form-label">${page.lang().get("hits_day")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input " value="${page.info().s("vod_hits_day")}" placeholder="" id="vod_hits_day" name="vod_hits_day">
                            </div>
                            <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_jumpurl")}" placeholder="" name="vod_jumpurl" id="vod_jumpurl">
                            </div>
                        </div>


                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("score")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_score")}" placeholder="" id="vod_score" name="vod_score">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_score_all")}" placeholder="" id="vod_score_all" name="vod_score_all">
                            </div>
                            <label class="layui-form-label">${page.lang().get("score_num")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_score_num")}" placeholder="" id="vod_score_num" name="vod_score_num">
                            </div>
                        </div>

                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("points_all")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_points")}" placeholder="" name="vod_points">
                            </div>
                            <label class="layui-form-label">${page.lang().get("points_play")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_points_play")}" placeholder="" name="vod_points_play">
                            </div>
                            <label class="layui-form-label">${page.lang().get("points_down")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_points_down")}" placeholder="" name="vod_points_down">
                            </div>
                        </div>

                        <div class="layui-form-item">

                            <label class="layui-form-label">${page.lang().get("admin/vod/tpl")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_tpl")}" placeholder="" name="vod_tpl">
                            </div>
                            <label class="layui-form-label">${page.lang().get("admin/vod/tpl_play")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_tpl_play")}" placeholder="" name="vod_tpl_play">
                            </div>
                            <label class="layui-form-label">${page.lang().get("admin/vod/tpl_down")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_tpl_down")}" placeholder="" name="vod_tpl_down">
                            </div>
                        </div>
                        <div class="layui-form-item">
                            <label class="layui-form-label">${page.lang().get("pwd_detail")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_pwd")}" placeholder="${page.lang().get("not_static_is_ok")}" name="vod_pwd">
                            </div>
                            <label class="layui-form-label">${page.lang().get("pwd_url")}：</label>
                            <div class="layui-input-inline ">
                                <input type="text" class="layui-input" value="${page.info().s("vod_pwd_url")}" placeholder="" name="vod_pwd_url">
                            </div>
                        </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pwd_play")}：</label>
                        <div class="layui-input-inline ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_pwd_play")}" placeholder="" name="vod_pwd_play">
                        </div>
                        <label class="layui-form-label">${page.lang().get("pwd_url")}：</label>
                        <div class="layui-input-inline ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_pwd_play_url")}" placeholder="" name="vod_pwd_play_url">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pwd_down")}：</label>
                        <div class="layui-input-inline ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_pwd_down")}" placeholder="" name="vod_pwd_down">
                        </div>
                        <label class="layui-form-label">${page.lang().get("pwd_url")}：</label>
                        <div class="layui-input-inline ">
                            <input type="text" class="layui-input" value="${page.info().s("vod_pwd_down_url")}" placeholder="" name="vod_pwd_down_url">
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
<script type="text/javascript" src="${page.head().assetsPath()}/vod-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=vod"}" data-shot-url="${page.url().build("upload/upload") + "?flag=vod_screenshot"}" data-file-url="${page.url().build("upload/upload") + "?flag=vod_file"}" data-extend-url="${page.url().build("type/extend")}" data-annex-del-url="${page.url().build("annex/del")}" data-img-key="${page.img().imgKey()}" data-img-api="${page.img().imgApi()}" data-type-id="${page.editing("vod_id") ? page.info().s("type_id") : ""}" data-players-len="${page.count("playList")}" data-downers-len="${page.count("downList")}" data-plots-len="${page.count("plotList")}" data-player-options="${ContentFormView.options(page.x("playerList"))}" data-downer-options="${ContentFormView.options(page.x("downerList"))}" data-server-options="${ContentFormView.options(page.x("serverList"))}" data-lang="${page.langJson("name_empty", "upload_ing", "del", "request_err", "admin/vod/douban_id_empty", "wait_submit", "search_data", "issue", "admin/vod/complete_works", "the", "episode", "play", "down", "admin/vod/plot", "admin/vod/select_player", "admin/vod/select_server", "admin/vod/select_downer", "remarks", "clear", "admin/vod/move_up", "admin/vod/move_down", "admin/vod/correct", "admin/vod/reverse_order", "admin/vod/del_prefix", "upload", "admin/vod/plot_name")}"></script>
`)
