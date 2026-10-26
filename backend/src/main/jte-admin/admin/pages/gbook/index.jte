@import com.maccms.backend.admin.web.view.content.ContentListView
@import com.maccms.backend.content.application.view.AdminRow
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >
        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <div class="layui-input-inline w100">
                    <select name="status">
                        <option value="">${page.lang().get("select_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("reviewed_not")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("reviewed")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w100">
                    <select name="type">
                        <option value="">${page.lang().get("select_reply_status")}</option>
                        <option value="1" selected="${page.pEq("reply", "1")}">${page.lang().get("reply_not")}</option>
                        <option value="2" selected="${page.pEq("reply", "2")}">${page.lang().get("reply_yes")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w100">
                    <select name="type">
                        <option value="">${page.lang().get("select_genre")}</option>
                        <option value="1" selected="${page.pEq("type", "1")}">${page.lang().get("gbook")}</option>
                        <option value="2" selected="${page.pEq("type", "2")}">${page.lang().get("report")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd() == null ? "" : page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>
        <div class="layui-btn-group">
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=gbook&col=gbook_status&tpl=select_status&url=gbook/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("del") + "?all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>
        </div>
    </div>


        <form class="layui-form" method="post" id="pageListForm" >
            <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="60">${page.lang().get("id")}</th>
                <th width="60">${page.lang().get("status")}</th>
                <th width="60">${page.lang().get("genre")}</th>
                <th >${page.lang().get("gbook")}</th>
                <th >${page.lang().get("report")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("gbook_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("gbook_id")}</td>
                <td>@if(vo.n("gbook_status") == 0)<span class="layui-badge">${page.lang().get("reviewed_not")}</span>@else<span class="layui-badge layui-bg-green">${page.lang().get("reviewed")}</span>@endif</td>
                <td>@if(vo.n("gbook_rid") == 0)${page.lang().get("gbook")}@else${page.lang().get("report")}@endif</td>
                <td>
                    <div class="c-999 f-12">
                        <u style="cursor:pointer" class="text-primary">${vo.s("gbook_name")}：</u>
                        <time>【@if(page.isToday(vo.n("gbook_time")))<font color="#FF0000">${page.day(vo.n("gbook_time"))}</font>@else${page.day(vo.n("gbook_time"))}@endif】</time>
                        <span class="ml-20">ip：【${ContentListView.ip(vo.n("gbook_ip"))}】</span>
                    </div>
                    <div class="f-12 c-999">
                        <span class="ml-20">${page.lang().get("status")}：</span>
                        ${page.lang().get("gbook")}：${page.restored(vo.s("gbook_content"))}
                    </div>
                </td>
                <td>
                    <div class="c-999 f-12">
                        ${page.lang().get("reply_time")}：@if(page.isToday(vo.n("gbook_reply_time")))<font color="#FF0000">${page.day(vo.n("gbook_reply_time"))}</font>@else${page.day(vo.n("gbook_reply_time"))}@endif
                    </div>
                    <div class="f-12 c-999">
                        ${page.lang().get("reply")}：${page.restored(vo.s("gbook_reply"))}
                    </div>
                    <div> </div>
                </td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("gbook_id"))}" href="javascript:;" title="${page.lang().get("reply")}">${page.lang().get("reply")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("gbook_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
