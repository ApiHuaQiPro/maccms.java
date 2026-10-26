@import com.maccms.backend.admin.web.view.content.TypeListView
@import com.maccms.backend.content.application.view.AdminTypeNode
@param TypeListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">
        <div class="layui-btn-group">
            <a data-full="1" data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("batch")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe642;</i>${page.lang().get("edit")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=type&col=type_status&tpl=select_status&url=type/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=type&col=type_status&tpl=select_type&url=type/move"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("transfer")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
        <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th>${page.lang().get("name")}</th>
                <th width="50">${page.lang().get("status")}</th>
                <th width="40">${page.lang().get("genre")}</th>
                <th width="40">${page.lang().get("sort")}</th>
                <th width="80">${page.lang().get("name")}</th>
                <th width="120">${page.lang().get("en")}</th>
                <th width="100">${page.lang().get("admin/type/type_tpl")}</th>
                <th width="100">${page.lang().get("admin/type/show_tpl")}</th>
                <th width="100">${page.lang().get("admin/type/detail_tpl")}</th>
                <th width="130">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminTypeNode vo : page.list())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.row().s("type_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.row().s("type_id")}、<a target="_blank" class="layui-badge-rim " href="${page.links().type(vo.row())}">${vo.row().s("type_name")}</a> <span class="layui-badge">${vo.count()}</span></td>
                <td>
                    <input type="checkbox" name="status" checked="${vo.row().n("type_status") == 1}" value="${vo.row().s("type_status")}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("open")}|${page.lang().get("close")}" data-href="${page.url().build("field?col=type_status&ids=" + vo.row().s("type_id"))}">
                </td>
                <td>
                    <span class="label label-success radius	">${page.midText(vo.row().n("type_mid"))}</span>
                </td>
                <td><input type="input" name="type_sort_${vo.row().s("type_id")}" value="${vo.row().s("type_sort")}" class="layui-input"></td>
                <td><input type="input" name="type_name_${vo.row().s("type_id")}" value="${vo.row().s("type_name")}" class="layui-input"></td>
                <td><input type="input" name="type_en_${vo.row().s("type_id")}" value="${vo.row().s("type_en")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_${vo.row().s("type_id")}" value="${vo.row().s("type_tpl")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_list_${vo.row().s("type_id")}" value="${vo.row().s("type_tpl_list")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_detail_${vo.row().s("type_id")}" value="${vo.row().s("type_tpl_detail")}" class="layui-input"></td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.row().s("type_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.row().s("type_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info") + "?pid=" + vo.row().s("type_id")}" href="javascript:;" title="${page.lang().get("add")}">${page.lang().get("add")}</a>
                </td>
            </tr>
            @for(AdminTypeNode ch : vo.children())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${ch.row().s("type_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>&nbsp;&nbsp;&nbsp;&nbsp;├&nbsp;${ch.row().s("type_id")}、<a target="_blank" class="layui-badge-rim " href="${page.links().type(ch.row())}">${ch.row().s("type_name")}</a> <span class="layui-badge">${ch.count()}</span></td>
                <td>
                    <input type="checkbox" name="status" checked="${ch.row().n("type_status") == 1}" value="${ch.row().s("type_status")}" lay-skin="switch" lay-filter="switchStatus" lay-text="${page.lang().get("open")}|${page.lang().get("close")}" data-href="${page.url().build("field?col=type_status&ids=" + ch.row().s("type_id"))}">
                </td>
                <td>
                    <span class="label label-success radius	">${page.midText(ch.row().n("type_mid"))}</span>
                </td>
                <td><input type="input" name="type_sort_${ch.row().s("type_id")}" value="${ch.row().s("type_sort")}" class="layui-input"></td>
                <td><input type="input" name="type_name_${ch.row().s("type_id")}" value="${ch.row().s("type_name")}" class="layui-input"></td>
                <td><input type="input" name="type_en_${ch.row().s("type_id")}" value="${ch.row().s("type_en")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_${ch.row().s("type_id")}" value="${ch.row().s("type_tpl")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_list_${ch.row().s("type_id")}" value="${ch.row().s("type_tpl_list")}" class="layui-input"></td>
                <td><input type="input" name="type_tpl_detail_${ch.row().s("type_id")}" value="${ch.row().s("type_tpl_detail")}" class="layui-input"></td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + ch.row().s("type_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + ch.row().s("type_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
            @endfor
            </tbody>
        </table>

    </form>
</div>
`)
