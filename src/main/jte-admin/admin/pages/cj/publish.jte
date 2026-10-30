@import com.maccms.backend.admin.web.view.collect.CjPublishView
@import com.maccms.backend.content.application.view.AdminRow
@param CjPublishView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="${page.tab("") ? "layui-this" : null}"><a href="${page.url().build("cj/publish") + "?id=" + page.id()}">${page.lang().get("all")}</a></li>
                <li class="${page.tab("1") ? "layui-this" : null}"><a href="${page.url().build("cj/publish") + "?id=" + page.id() + "&status=1"}">${page.lang().get("admin/cj/collected_not")}</a></li>
                <li class="${page.tab("2") ? "layui-this" : null}"><a href="${page.url().build("cj/publish") + "?id=" + page.id() + "&status=2"}">${page.lang().get("admin/cj/collected")}</a></li>
                <li class="${page.tab("3") ? "layui-this" : null}"><a href="${page.url().build("cj/publish") + "?id=" + page.id() + "&status=3"}">${page.lang().get("admin/cj/published")}</a></li>
            </ul>

            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">
                    <div class="layui-btn-group">
                        <a data-href="${page.url().build("content_del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
                        <a data-href="${page.url().build("content_del") + "?ids=1&all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>
                        <a data-href="${page.url().build("content_into") + "?id=" + page.id()}" data-ajax="no" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe654;</i>${page.lang().get("import")}</a>
                        <a data-href="${page.url().build("content_into") + "?id=" + page.id() + "&all=1"}" data-ajax="no" data-checkbox="no" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe654;</i>${page.lang().get("import_all")}</a>
                    </div>
                    <table class="layui-table" lay-size="sm">
                        <thead>
                        <tr>
                            <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                            <th width="50">${page.lang().get("id")}</th>
                            <th width="50">${page.lang().get("status")}</th>
                            <th width="250">${page.lang().get("name")}</th>
                            <th >${page.lang().get("url")}</th>
                            <th width="40">${page.lang().get("opt")}</th>
                        </tr>
                        </thead>
                        <tbody>
                        @for(AdminRow vo : page.list().rows())
                        <tr>
                            <td><input type="checkbox" name="ids[]" value="${vo.s("id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                            <td>${vo.s("id")}</td>
                            <td>${page.statusText(vo)}</td>
                            <td>${page.text(vo.s("title"))}</td>
                            <td>${page.text(vo.s("url"))}</td>
                            <td>
                                <a class="layui-badge-rim j-iframe " data-href="${page.url().build("show?id=" + vo.s("id"))}" href="javascript:;" title="${page.lang().get("view")}">${page.lang().get("view")}</a>
                            </td>
                        </tr>
                        @endfor
                        </tbody>
                    </table>

                    <div id="pages" class="center"></div>

                </div>

            </div>
        </div>

    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/admin-list.js" data-cur-url="${page.curUrl()}" data-count="${page.list().total()}" data-limit="${page.list().limit()}" data-curr="${page.list().page()}"></script>
`)
