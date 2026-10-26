@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configupload/title")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configupload/tip")}${page.t(page.at("sys_temp_dir"))}<br>
                        @if(page.eq(page.at("temp_writable"), true))<span class="layui-badge layui-bg-green">${page.lang().get("admin/system/configupload/write_ok")}</span>@else<span class="layui-badge">${page.lang().get("admin/system/configupload/write_err")}</span>@endif
                    </blockquote>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/img_key")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" name="upload[img_key]" placeholder="" value="${page.v(page.at("config.upload.img_key"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/img_key_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/img_api")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" name="upload[img_api]" placeholder="" value="${page.v(page.at("config.upload.img_api"))}" class="layui-input">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/img_api_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("pic_thumb")}：</label>
                        <div class="layui-input-inline">
                            <input type="radio" name="upload[thumb]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.upload.thumb"), 1L)}">
                            <input type="radio" name="upload[thumb]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.upload.thumb"), 1L)}">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/thumb_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/thumb_size")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="upload[thumb_size]" placeholder="" value="${page.v(page.at("config.upload.thumb_size"))}" class="layui-input w150">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/thumb_size_tip")}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/thumb_type")}：</label>
                        <div class="layui-input-inline">
                            <select class="w150" name="upload[thumb_type]">
                                <option value="1" selected="${page.eq(page.at("config.upload.thumb_type"), 1L)}">${page.lang().get("admin/system/configupload/thumb_type1")}</option>
                                <option value="2" selected="${page.eq(page.at("config.upload.thumb_type"), 2L)}">${page.lang().get("admin/system/configupload/thumb_type2")}</option>
                                <option value="3" selected="${page.eq(page.at("config.upload.thumb_type"), 3L)}">${page.lang().get("admin/system/configupload/thumb_type3")}</option>
                                <option value="4" selected="${page.eq(page.at("config.upload.thumb_type"), 4L)}">${page.lang().get("admin/system/configupload/thumb_type4")}</option>
                                <option value="5" selected="${page.eq(page.at("config.upload.thumb_type"), 5L)}">${page.lang().get("admin/system/configupload/thumb_type5")}</option>
                                <option value="6" selected="${page.eq(page.at("config.upload.thumb_type"), 6L)}">${page.lang().get("admin/system/configupload/thumb_type6")}</option>
                            </select>
                        </div>
                        <div class="layui-form-mid layui-word-aux"></div>
                    </div>
                <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/watermark")}：</label>
                        <div class="layui-input-inline">
                            <input type="radio" name="upload[watermark]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.upload.watermark"), 1L)}">
                            <input type="radio" name="upload[watermark]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.upload.watermark"), 1L)}">
                        </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/watermark_location")}：</label>
                        <div class="layui-input-inline">
                            <select class="w150" name="upload[watermark_location]">

                                <option value="1" selected="${page.eq(page.at("config.upload.watermark_location"), 1L)}">${page.lang().get("admin/system/configupload/watermark_location1")}</option>
                                <option value="2" selected="${page.eq(page.at("config.upload.watermark_location"), 2L)}">${page.lang().get("admin/system/configupload/watermark_location2")}</option>
                                <option value="3" selected="${page.eq(page.at("config.upload.watermark_location"), 3L)}">${page.lang().get("admin/system/configupload/watermark_location3")}</option>
                                <option value="4" selected="${page.eq(page.at("config.upload.watermark_location"), 4L)}">${page.lang().get("admin/system/configupload/watermark_location4")}</option>
                                <option value="5" selected="${page.eq(page.at("config.upload.watermark_location"), 5L)}">${page.lang().get("admin/system/configupload/watermark_location5")}</option>
                                <option value="6" selected="${page.eq(page.at("config.upload.watermark_location"), 6L)}">${page.lang().get("admin/system/configupload/watermark_location6")}</option>
                                <option value="7" selected="${page.eq(page.at("config.upload.watermark_location"), 7L)}">${page.lang().get("admin/system/configupload/watermark_location7")}</option>
                                <option value="8" selected="${page.eq(page.at("config.upload.watermark_location"), 8L)}">${page.lang().get("admin/system/configupload/watermark_location8")}</option>
                                <option value="9" selected="${page.eq(page.at("config.upload.watermark_location"), 9L)}">${page.lang().get("admin/system/configupload/watermark_location9")}</option>


                            </select>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/watermark_content")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="upload[watermark_content]" placeholder="" value="${page.v(page.at("config.upload.watermark_content"))}" class="layui-input w150"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/watermark_size")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="upload[watermark_size]" placeholder="${page.lang().get("admin/system/configupload/watermark_size_tip")}" value="${page.v(page.at("config.upload.watermark_size"))}" class="layui-input w150"  >
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/watermark_color")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="upload[watermark_color]" placeholder="${page.lang().get("admin/system/configupload/watermark_color_tip")}" value="${page.v(page.at("config.upload.watermark_color"))}" class="layui-input w150"  >
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configupload/protocol")}：</label>
                        <div class="layui-input-inline">
                            <select class="w150" name="upload[protocol]" lay-filter="upload[protocol]">
                                <option value="http" selected="${page.eq(page.at("config.upload.protocol"), "http")}">http</option>
                                <option value="https" selected="${page.eq(page.at("config.upload.protocol"), "https")}">https</option>
                            </select>
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/protocol_tip")}</div>
                    </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configupload/mode")}：</label>
                    <div class="layui-input-inline">
                        <select class="w150" name="upload[mode]" lay-filter="upload[mode]">
                            <option value="local" selected="${page.eq(page.at("config.upload.mode"), "local")}">${page.lang().get("admin/system/configupload/mode_local")}</option>
                            <option value="remote" selected="${page.eq(page.at("config.upload.mode"), "remote")}">${page.lang().get("admin/system/configupload/mode_remote")}</option>
                            @for(var item_vo : page.each(page.at("extends.ext_list")))
                            <option value="${page.v(item_vo.key())}" selected="${page.eq(page.at("config.upload.mode"), item_vo.key())}">${page.t(item_vo.value())}</option>
                            @endfor
                        </select>
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configupload/keep_local")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="upload[keep_local]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.upload.keep_local"), 1L)}">
                        <input type="radio" name="upload[keep_local]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.upload.keep_local"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configupload/keep_local_tip")}</div>
                </div>

                <div class="layui-form-item upload_mode mode_remote" style="${!page.eq(page.at("config.upload.mode"), "remote") ? "display:none;" : null}">
                    <label class="layui-form-label">${page.lang().get("admin/system/configupload/remoteurl")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="upload[remoteurl]" placeholder="${page.lang().get("admin/system/configupload/remoteurl_tip")}" value="${page.v(page.at("config.upload.remoteurl"))}" class="layui-input w500">
                    </div>
                </div>

                @template.admin.pages.system.extend.html(page = page, flag = "upload")

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
<script type="text/javascript" src="${page.head().assetsPath()}/system-configupload.js"></script>
`)
