@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/template/ads.html。复制按钮的脚本移到 template-ads.js（内容安全策略不允许内联脚本）；
     未使用的 data_info/data_del 不再输出；多出的 </a>、</tbody> 去掉。调用代码一栏照旧是 <script src="{$maccms.path_ads}文件名"></script>。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-btn-box lh30" >
        <div class="layui-btn-group fl">
            <a data-full="1" data-href="${page.url("info")}?fpath=${page.s(page.at("curpath"))}&fname=" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
        </div>
        <div class="page-filter fr" >

        </div>
    </div>

    <form class="layui-form layui-form-pane" action="">
        <table class="layui-table mt10">
        <thead>
        <tr>
            <th>${page.lang().get("file_name")}</th>
            <th width="150">${page.lang().get("file_des")}</th>
            <th width="150">${page.lang().get("file_size")}</th>
            <th width="150">${page.lang().get("file_time")}</th>
            <th width="200">${page.lang().get("admin/template/call_code")}</th>
            <th width="130">${page.lang().get("opt")}</th>
        </tr>
        </thead>
        <tbody>
            @for(var vo : page.each(page.at("files")))
            <tr>

                <th>${page.t(page.at(vo.value(), "name"))}</th>
                <td>${page.t(page.at(vo.value(), "note"))}</td>
                <td>${page.t(page.at(vo.value(), "size"))}</td>
                <td>@if(page.eq(page.at(vo.value(), "today"), "1"))<font color="#FF0000">${page.t(page.at(vo.value(), "time_text"))}</font>@else${page.t(page.at(vo.value(), "time_text"))}@endif</td>
                <td>
                    <input id="txt${page.s(vo.key())}" type="text" value="${"<script src=\"{$maccms.path_ads}" + page.s(page.at(vo.value(), "name")) + "\"></script>"}" size="60">
                </td>
                <td>
                    <a class="layui-badge-rim j-clipboard" data-clipboard-target="#txt${page.s(vo.key())}" href="javascript:;" title="${page.lang().get("copy")}">${page.lang().get("copy")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url("info")}?fpath=${page.s(page.at(vo.value(), "path"))}&fname=${page.s(page.at(vo.value(), "name"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url("del")}?fname=${page.s(page.at(vo.value(), "fullname"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
        </tbody>
        <tfoot>
            <tr><td colspan="6">${page.lang().get("admin/template/current_dir")}：${page.s(page.at("curpath")).replace('@', '/')}，${page.lang().get("sum")}<b class="red">${page.s(page.at("num_file"))}</b>${page.lang().get("file")},${page.lang().get("occupies")}<b class="red">${page.s(page.at("sum_size"))}</b>${page.lang().get("space")}</td></tr>
        </tfoot>
    </table>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.clipboard.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/template-ads.js"></script>
`)
