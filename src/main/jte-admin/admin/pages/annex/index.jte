@import com.maccms.backend.admin.web.view.content.ContentListView
@import com.maccms.backend.content.application.PhpNumbers
@import com.maccms.backend.content.application.view.AdminRow
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">
        @if(!page.pEq("select", "1"))
        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <input type="hidden" value="${page.p("select")}" name="select">
                <input type="hidden" value="${page.p("input")}" name="input">
                <div class="layui-input-inline w150">
                    <select name="type">
                        <option value="">${page.lang().get("select_type")}</option>
                        <option value="image" selected="${page.pEq("type", "image")}">image</option>
                        <option value="media" selected="${page.pEq("type", "media")}">media</option>
                        <option value="file" selected="${page.pEq("type", "file")}">file</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="order">
                        <option value="">${page.lang().get("select_sort")}</option>
                        <option value="annex_time" selected="${page.pEq("order", "annex_time")}">${page.lang().get("update_time")}</option>
                        <option value="annex_id" selected="${page.pEq("order", "annex_id")}">${page.lang().get("id")}</option>
                        <option value="annex_hits" selected="${page.pEq("order", "annex_size")}">${page.lang().get("file_size")}</option>
                    </select>
                </div>

                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd() == null ? "" : page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>
        @endif

        <div class="layui-btn-group">
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a href="${page.url().build("file")}" class="layui-btn layui-btn-primary "><i class="layui-icon">&#xe620;</i>${page.lang().get("admin/annex/dir_model")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("annex/check")}" href="javascript:;" title="${page.lang().get("admin/annex/check")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("admin/annex/check")}</a>
            <a href="${page.url().build("init")}" class="layui-btn layui-btn-primary "><i class="layui-icon">&#xe620;</i>${page.lang().get("admin/annex/init_data")}</a>
        </div>
    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="50">${page.lang().get("id")}</th>
                <th width="150">${page.lang().get("file_size")}</th>
                <th width="150">${page.lang().get("type")}</th>
                <th width="150">${page.lang().get("update_time")}</th>
                <th >${page.lang().get("file_name")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("annex_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("annex_id")}</td>
                <td>${PhpNumbers.round2Text(vo.n("annex_size") / 1024.0)}K</td>
                <td>${vo.s("annex_type")}</td>
                <td>@if(page.isToday(vo.n("annex_time")))<font color="#FF0000">${page.day(vo.n("annex_time"))}</font>@else${page.day(vo.n("annex_time"))}@endif</td>
                <td><a target="_blank" class="layui-badge-rim " href="${page.attr(page.<String>x("macPath") + vo.s("annex_file"))}">${page.text(vo.s("annex_file"))}</a></td>
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
