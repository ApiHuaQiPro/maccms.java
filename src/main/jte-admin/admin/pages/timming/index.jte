@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="my-toolbar-box">

        <div class="layui-btn-group">
            <a data-href="${page.url("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url("index/select")}?tab=vod&col=status&tpl=select_state&url=timming/field" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>

                <th width="80">${page.lang().get("name")}</th>
                <th width="150">${page.lang().get("description")}</th>
                <th width="80">${page.lang().get("run")}</th>
                <th width="80">${page.lang().get("status")}</th>
                <th width="80">${page.lang().get("last_run_time")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            @for(var item_vo : page.each(page.at("list")))
            <tr>
                <td><input type="checkbox" name="ids[]" value="${page.v(page.at(item_vo.value(), "name"))}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${page.t(page.at(item_vo.value(), "name"))}</td>
                <td>${page.t(page.at(item_vo.value(), "des"))}</td>
                <td>${page.t(page.at(item_vo.value(), "file"))}</td>
                <td>
                    <input type="checkbox" name="status" checked="${page.eq(page.at(item_vo.value(), "status"), 1L)}" value="${page.v(page.at(item_vo.value(), "status"))}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("open")}|${page.lang().get("close")}" data-href="${page.url("field?col=status&ids=" + page.s(page.at(item_vo.value(), "name")))}">
                </td>
                <td>${page.t(page.macDay(page.at(item_vo.value(), "runtime")))}</td>
                <td>
                    <a class="layui-badge-rim" target="_blank" href="${page.s(page.at("config.site.install_dir"))}api.php/timming/index.html?enforce=1&name=${page.v(page.rawurlencode(page.at(item_vo.value(), "name")))}" title="${page.lang().get("test")}">${page.lang().get("test")}</a>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url("info")}?id=${page.v(page.rawurlencode(page.at(item_vo.value(), "name")))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url("del")}?ids=${page.v(page.rawurlencode(page.at(item_vo.value(), "name")))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
        </table>

    </form>
</div>
`)
