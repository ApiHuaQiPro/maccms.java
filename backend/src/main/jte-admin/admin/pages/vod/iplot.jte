@import com.maccms.backend.admin.web.view.content.ContentFormView
@import com.maccms.backend.content.application.view.PlotDto
@import java.util.List
@param ContentFormView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.jscolor.js"></script>
@template.admin.partials.editor(head = page.head(), serverUrl = page.url().build("upload/upload") + "?from=ueditor&flag=" + page.editor() + "&input=upfile")
`, content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>
    
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="vod_id" value="${page.info().s("vod_id")}">
        <input type="hidden" name="vod_name" value="${page.info().s("vod_name")}">
        <input type="hidden" name="vod_en" value="${page.info().s("vod_en")}">
        <input type="hidden" name="type_id" value="${page.info().s("type_id")}">

        <div class="layui-tab">
            <ul class="layui-tab-title ">
                <li class="layui-this">${page.lang().get("admin/vod/episode_plot")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">
                    <div id="plot_list" class="contents">
                        @for(PlotDto vo : page.<java.util.List<com.maccms.backend.content.application.view.PlotDto>>x("plotList"))
                        <div class="layui-form-item" data-i="${vo.index()}">
                            <label class="layui-form-label">${page.lang().get("admin/vod/plot")}${vo.index()}：</label>
                            <div class="layui-input-inline w500"><input type="text" name="vod_plot_name[]" class="layui-input" value="${vo.name()}" placeholder="${page.lang().get("admin/vod/plot_name")}"></div>
                            <div class="layui-input-inline w400 p10"><a href="javascript:void(0)" class="j-editor-clear">${page.lang().get("clear")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">${page.lang().get("del")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">${page.lang().get("admin/vod/move_up")}</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">${page.lang().get("admin/vod/move_down")}</a><br></div>
                            <div class="p10 m20"> </div>
                            <div class="layui-input-block "><textarea id="vod_plot_detail${vo.index()}" name="vod_plot_detail[]" type="text/plain" style="width:100%;height:150px">${page.strCorrect(vo.detail())}</textarea></div>
                            </div>
                        @endfor
                    </div>
                    <div class="layui-form-item">
                        <label class=""><button class="layui-btn radius j-plot-add" type="button">${page.lang().get("add_group")}</button></label>
                        <div class="layui-input-block">

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
<script type="text/javascript" src="${page.head().assetsPath()}/vod-iplot.js" data-plots-len="${page.count("plotList")}" data-lang="${page.langJson("name_empty", "admin/vod/plot", "admin/vod/plot_name", "clear", "del", "admin/vod/move_up", "admin/vod/move_down")}"></script>
`)
