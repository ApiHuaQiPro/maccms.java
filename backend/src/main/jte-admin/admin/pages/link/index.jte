@import com.maccms.backend.admin.web.view.content.LinkListView
@import com.maccms.backend.content.application.view.AdminRow
@param LinkListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">
        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("index")}">
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("batch")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe642;</i>${page.lang().get("edit")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
        <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="100">${page.lang().get("id")}</th>
                <th width="100">${page.lang().get("sort")}</th>
                <th width="150">${page.lang().get("genre")}</th>
                <th >${page.lang().get("name")}</th>
                <th >${page.lang().get("url")}</th>
                <th >${page.lang().get("logo")}</th>
                <th width="130">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.list().rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("link_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("link_id")}</td>
                <td><input type="input" name="link_sort[]" value="${page.attr(vo.s("link_sort"))}" class="layui-input"></td>
                <td>
                    <select name="link_type[]">
                        <option value="0" selected="${vo.n("link_type") == 0}">${page.lang().get("admin/link/text_link")}</option>
                        <option value="1" selected="${vo.n("link_type") == 1}">${page.lang().get("admin/link/pic_link")}</option>
                    </select>
                </td>
                <td><input type="input" name="link_name[]" value="${page.attr(vo.s("link_name"))}" class="layui-input"></td>
                <td><input type="input" name="link_url[]" value="${page.attr(vo.s("link_url"))}" class="layui-input"></td>
                <td><input type="input" name="link_logo[]" value="${page.attr(vo.s("link_logo"))}" class="layui-input"></td>
                <td>
                    <a class="layui-badge-rim j-ajax" data-href="${page.url().build("index/check_back_link") + "?url=" + vo.s("link_url")}" refresh="no" href="javascript:;" title="${page.lang().get("detect")}">${page.lang().get("detect")}</a>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("link_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("link_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>
        <div id="pages" class="center"></div>

    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/admin-list.js" data-cur-url="${page.curUrl()}" data-count="${page.list().total()}" data-limit="${page.list().limit()}" data-curr="${page.list().page()}"></script>
`)
