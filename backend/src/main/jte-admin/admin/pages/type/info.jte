@import com.maccms.backend.admin.web.view.content.TypeInfoView
@import com.maccms.backend.admin.web.view.AdminText
@import com.maccms.backend.content.application.view.AdminRow
@param TypeInfoView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="type_id" value="${page.v("type_id")}">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <blockquote class="layui-elem-quote layui-quote-nm">
            ${AdminText.trusted(page.lang().get("admin/type/tip"))}
        </blockquote>

        <div class="layui-form-item">
            <label class="layui-form-label"> ${page.lang().get("genre")}：</label>
            <div class="layui-input-inline ">
                    <select id="type_mid" name="type_mid" lay-filter="type_mid">
                        <option value="1" selected="${page.midSelected(1)}">${page.lang().get("vod")}</option>
                        <option value="2" selected="${page.midSelected(2)}">${page.lang().get("art")}</option>
                        <option value="8" selected="${page.midSelected(8)}">${page.lang().get("actor")}</option>
                        <option value="11" selected="${page.midSelected(11)}">${page.lang().get("website")}</option>
                    </select>
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/parent_type")}：</label>
            <div class="layui-input-inline ">
                    <select name="type_pid">
                        <option value="0">${page.lang().get("admin/type/top_type")}</option>
                        @for(AdminRow vo : page.parents())
                        <option value="${vo.s("type_id")}" selected="${page.parentSelected(vo)}">${page.text(vo.s("type_name"))}</option>
                        @endfor
                    </select>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("status")}：</label>
            <div class="layui-input-block">
                <input name="type_status" type="radio" id="rad-1" value="0" title="${page.lang().get("disable")}" checked="${!page.enabled()}">
                <input name="type_status" type="radio" id="rad-2" value="1" title="${page.lang().get("enable")}" checked="${page.enabled()}">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("sort")}：</label>
            <div class="layui-input-inline w100">
                <input type="text" class="layui-input" value="${page.v("type_sort")}" placeholder="" id="type_sort" name="type_sort">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("name")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" lay-verify="type_name" value="${page.v("type_name")}" placeholder="" id="type_name" name="type_name">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("en")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_en")}" placeholder="" id="type_en" name="type_en">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/type_tpl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" lay-verify="type_tpl" value="${page.v("type_tpl")}" placeholder="" id="type_tpl" name="type_tpl">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/show_tpl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" lay-verify="type_tpl_list" value="${page.v("type_tpl_list")}" placeholder="" id="type_tpl_list" name="type_tpl_list">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/detail_tpl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_tpl_detail")}" placeholder="" id="type_tpl_detail" name="type_tpl_detail">
            </div>
        </div>

        <div class="layui-form-item vod-list">
            <label class="layui-form-label">${page.lang().get("admin/type/play_tpl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_tpl_play")}" placeholder="" id="type_tpl_play" name="type_tpl_play">
            </div>
        </div>
        <div class="layui-form-item vod-list">
            <label class="layui-form-label">${page.lang().get("admin/type/down_tpl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_tpl_down")}" placeholder="" id="type_tpl_down" name="type_tpl_down">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("seo_title")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_title")}" placeholder="" id="type_title" name="type_title">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("seo_key")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_key")}" placeholder="" id="type_key" name="type_key">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("seo_des")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_des")}" placeholder="" id="type_des" name="type_des">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/logo")}：</label>
            <div class="layui-input-inline w600">
                <input type="text" name="type_logo" placeholder="" value="${page.v("type_logo")}" class="layui-input upload-input">
            </div>
            <div class="layui-input-inline ">
                <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'type_logo'}}" id="upload1">${page.lang().get("upload_pic")}</button>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/type/pic")}</label>
            <div class="layui-input-inline w600">
                <input type="text" name="type_pic" placeholder="" value="${page.v("type_pic")}" class="layui-input upload-input">
            </div>
            <div class="layui-input-inline ">
                <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:'type_pic'}}" id="upload1">${page.lang().get("upload_pic")}</button>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("jumpurl")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.v("type_jumpurl")}" placeholder="" id="type_jumpurl" name="type_jumpurl">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("class")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("class")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[class]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}" >
            <label class="layui-form-label">${page.lang().get("extend_area")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("area")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[area]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("extend_lang")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("lang")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[lang]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("extend_year")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("year")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[year]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("admin/type/extend_star")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("star")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[star]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("admin/type/extend_director")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("director")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[director]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("admin/type/extend_state")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("state")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[state]">
            </div>
        </div>
        <div class="layui-form-item vod-list" style="${page.vodHidden() ? "display:none" : null}">
            <label class="layui-form-label">${page.lang().get("admin/type/extend_version")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.ext("version")}" placeholder="${page.lang().get("multi_separate_tip")}" name="type_extend[version]">
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/type-info.js" data-upload-url="${page.url().build("upload/upload") + "?flag=type"}" data-msg-uploading="${page.lang().get("upload_ing")}" data-msg-name="${page.lang().get("name_empty")}" data-msg-tpl="${page.lang().get("admin/type/tpl_empty")}" data-mid="${page.info().s("type_mid")}"></script>
`)
