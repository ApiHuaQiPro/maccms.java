@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configplay/title")}</li>
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">

                    <blockquote class="layui-elem-quote layui-quote-nm">
                        ${page.lang().get("admin/system/configplay/tip")}
                    </blockquote>

                <div class="layui-form-item" >
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/width")}：</label>
                    <div class="layui-input-inline w150">
                        <input type="text" name="play[width]" placeholder="${page.lang().get("admin/system/configplay/width_tip")}" value="${page.v(page.at("play.width"))}" class="layui-input ">
                    </div>
                </div>
                <div class="layui-form-item" >
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/height")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[height]" placeholder="${page.lang().get("admin/system/configplay/height_tip")}" value="${page.v(page.at("play.height"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item" >
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/widthmob")}：</label>
                    <div class="layui-input-inline w150">
                        <input type="text" name="play[widthmob]" placeholder="${page.lang().get("admin/system/configplay/width_tip")}" value="${page.v(page.at("play.widthmob"))}" class="layui-input ">
                    </div>
                </div>
                <div class="layui-form-item" >
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/heightmob")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[heightmob]" placeholder="${page.lang().get("admin/system/configplay/height_tip")}" value="${page.v(page.at("play.heightmob"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item" style="display:none;">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/widthpop")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[widthpop]" placeholder="${page.lang().get("admin/system/configplay/width_tip")}" value="${page.v(page.at("play.widthpop"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item" style="display:none;">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/heightpop")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[heightpop]" placeholder="${page.lang().get("admin/system/configplay/height_tip")}" value="${page.v(page.at("play.heightpop"))}" class="layui-input w150">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/second")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[second]" placeholder="${page.lang().get("admin/system/configplay/second_tip")}" value="${page.v(page.at("play.second"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/prestrain")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[prestrain]" placeholder="${page.lang().get("admin/system/configplay/prestrain_tip")}" value="${page.v(page.at("play.prestrain"))}" class="layui-input">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/buffer")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="play[buffer]" placeholder="${page.lang().get("admin/system/configplay/buffer_tip")}" value="${page.v(page.at("play.buffer"))}" class="layui-input">
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configplay/parse")}：</label>
                        <div class="layui-input-block">
                            <input type="text" name="play[parse]" placeholder="${page.lang().get("admin/system/configplay/parse_tip")}" value="${page.v(page.at("play.parse"))}" class="layui-input">
                        </div>
                    </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/autofull")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="play[autofull]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("play.autofull"), 1L)}">
                        <input type="radio" name="play[autofull]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("play.autofull"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item" style="display:none;">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/showtop")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="play[showtop]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("play.showtop"), 1L)}">
                        <input type="radio" name="play[showtop]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("play.showtop"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item" style="display:none;">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/showlist")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="play[showlist]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("play.showlist"), 1L)}">
                        <input type="radio" name="play[showlist]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("play.showlist"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/flag")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="play[flag]" value="0" title="${page.lang().get("admin/system/configplay/flag_tip")}" checked >
                    </div>
                </div>

                <div class="layui-form-item" style="display:none;">
                    <label class="layui-form-label">${page.lang().get("admin/system/configplay/colors")}：</label>
                    <div class="layui-input-block">
                        <input type="text" id="mac_colors" name="play[colors]" placeholder="" value="${page.v(page.at("play.colors"))}" class="layui-input">
                    </div>
                </div>
                    <div class="layui-form-item" style="display:none;">
                        <label class="layui-form-label">${page.lang().get("admin/system/configplay/select_colors")}：</label>
                        <div class="layui-input-block">
                            <button class="layui-btn btn-primary radius" type="button" data-color="1" style="background-color: #000000"> 全黑色</button>
                            <button class="layui-btn btn-primary radius" type="button" data-color="2" style="background-color: #EFF4F7"> 浅蓝色</button>
                            <button class="layui-btn btn-primary radius" type="button" data-color="3" style="background-color: #D8CFDF"> 浅紫色</button>
                            <button class="layui-btn btn-primary radius" type="button" data-color="4" style="background-color: #D7E7B6"> 浅绿色</button>
                        </div>
                    </div>

                    <blockquote class="layui-elem-quote layui-quote-nm" style="display:none;">
                        ${page.lang().get("admin/system/configplay/select_colors_tip")}
                    </blockquote>

            </div>
            </div>

        </div>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button type="button" class="layui-btn layui-btn-normal" id="btnDef">${page.lang().get("default_val")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/system-configplay.js"></script>
`)
