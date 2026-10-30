@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/template/info.html。jte 模板（D4，24 文档第 7 节）：提示信息换成新的安全过滤规则与前台当前使用的模板；
     保存失败（编译错误、过滤拒绝、他人修改）时错误显示在页面上、内容保留，表单令牌由响应换新（template-info.js）；
     有上一版时多一个"恢复上一版"按钮。js/xml/html 静态文件的提示照旧显示旧版的过滤字符串。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" method="post" action="" id="tplForm">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <input type="hidden" name="fversion" value="${page.s(page.at("fversion"))}" />
        <input type="hidden" name="op" value="" id="tplOp" />
        <blockquote class="layui-elem-quote layui-quote-nm">
            提示信息：<br>
            @if(page.eq(page.at("jte"), "1"))
            jte 模板保存时先在临时副本里把整套模板重新编译，通过后才写入并生效，失败时不写入、前台继续使用上一版；文件内出现以下写法时禁止在线保存：<br>
            @for(var r : page.each(page.at("rules")))
            ${page.s(r.value())}<br>
            @endfor
            ${page.s(page.at("engine"))}<br>
            @if(!page.s(page.at("unavailable")).isEmpty())
            <b class="red">${page.s(page.at("unavailable"))}</b><br>
            @endif
            jte 模板目录：${page.s(page.at("root"))}/
            @else
            为了安全考量避免通过模板写入后门文件，文件内出现以下任意字符串时禁止在线保存修改，如需修改请使用其他方式。<br>
            ${page.s(page.at("filter"))}
            @endif
        </blockquote>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("path")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.s(page.at("fpath"))}" placeholder="" id="fpath" name="fpath" readonly="readonly">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("file_name")}：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.s(page.at("fname"))}" placeholder="${page.s(page.at("name_tip"))}" id="fname" name="fname" readonly="${!page.s(page.at("fname")).isEmpty()}">
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("content")}：</label>
            <div class="layui-input-block">
                <pre class="layui-elem-quote layui-quote-nm red" id="tplError" hidden="hidden"></pre>
                <textarea name="fcontent" cols="" rows="" class="layui-textarea"  placeholder="" style="height:550px;">${page.s(page.at("fcontent"))}</textarea>
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="tplSubmit" data-op="">${page.lang().get("btn_save")}</button>
                @if(page.eq(page.at("previous"), "1"))
                <button type="submit" class="layui-btn layui-btn-normal" lay-submit="" lay-filter="tplSubmit" data-op="restore" data-confirm="恢复上一版后，当前内容会成为新的上一版，确定吗？">恢复上一版</button>
                @endif
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/template-info.js" data-msg-wait="数据提交中..."></script>
`)
