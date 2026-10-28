@import com.maccms.backend.admin.web.view.collect.CollectListView
@import com.maccms.backend.collect.application.ProvideKind
@import com.maccms.backend.content.application.view.AdminRow
@param CollectListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe" data-width="800px" data-height="610px"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("clearbind")}" class="layui-btn layui-btn-primary j-page-btns confirm" data-checkbox="false" data-ajax="yes"><i class="layui-icon">&#xe640;</i>${page.lang().get("admin/collect/clear_bind")}</a>

                @for(ProvideKind k : page.breakpoints())
                <a href="${page.breakHref(k)}" class="layui-btn layui-btn-danger ">${page.breakLabel(k)}</a>
                @endfor

        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="100">${page.lang().get("id")}</th>
                <th width="100">接口类型</th>
                <th width="100">资源类型</th>
                <th>资源站</th>
                <th width="200">采集选项</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>

            @for(AdminRow vo : page.list().rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("collect_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("collect_id")}</td>
                <td>${page.typeText(vo)} </td>
                <td>${page.midText(vo)}</td>
                <td><a class="layui-badge-rim" href="${page.api(vo, "list", "")}" title="进入资源库">【${page.text(vo.s("collect_name"))}】${page.text(vo.s("collect_url"))}</a></td>
                <td>
                    <a class="layui-badge-rim" href="${page.api(vo, "cj", "24")}" title="采集当天">采集当天</a>
                    <a class="layui-badge-rim" href="${page.api(vo, "cj", "168")}" title="采集本周">采集本周</a>
                    <a class="layui-badge-rim" href="${page.api(vo, "cj", "")}" title="采集所有">采集所有</a>
                </td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("collect_id"))}" data-width="800px" data-height="610px" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("collect_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
        </table>
        <div id="pages" class="center"></div>
    </form>
</div>
`)
