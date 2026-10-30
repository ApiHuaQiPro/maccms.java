@import com.maccms.backend.admin.web.view.content.ContentListView
@import com.maccms.backend.content.application.view.AdminRow
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >

        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <div class="layui-input-inline w150">
                    <select name="status">
                        <option value="">${page.lang().get("select_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("reviewed_not")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("reviewed")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>
        <div class="layui-btn-group">
            <a data-full="1" data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=topic&col=topic_level&tpl=select_level&url=topic/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("level")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=topic&col=topic_status&tpl=select_status&url=topic/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("images/opt?tab=topic")}" href="javascript:;" title="${page.lang().get("pic_sync")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("pic_sync")}</a>
        </div>
    </div>

    <form class="layui-form" method="post" id="pageListForm" >
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="100">${page.lang().get("id")}</th>
                <th >${page.lang().get("name")}</th>
                <th width="30">${page.lang().get("hits")}</th>
                <th width="30">${page.lang().get("score")}</th>
                <th width="30">${page.lang().get("level")}</th>
                <th width="30">${page.lang().get("browse")}</th>
                <th width="150">${page.lang().get("update_time")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("topic_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("topic_id")}</td>
                <td><a target="_blank" class="layui-badge-rim " href="${page.links().topic(vo)}">${vo.s("topic_name")}</a> @if(vo.n("topic_status") == 0) <span class="layui-badge">${page.lang().get("reviewed_not")}</span>@endif </td>
                <td>${vo.s("topic_hits")}</td>
                <td>${vo.s("topic_score")}</td>
                <td><a data-href="${page.url().build("index/select") + "?tab=topic&col=topic_level&tpl=select_level&url=topic/field&ids=" + vo.s("topic_id")}" data-width="270" data-height="100" class=" j-select"><span class="layui-badge layui-bg-orange">${vo.s("topic_level")}</span></a></td>
                <td>@if(page.made(vo, "topic_time", "topic_time_make"))<a target="_blank" class="layui-badge layui-bg-green " href="${page.links().topic(vo)}">Y</a>@else<a class="layui-badge" href="${page.url().build("make/make?ac=topic_info") + "?topic=" + vo.s("topic_id") + "&ref=1"}">N</a>@endif</td>
                <td>@if(page.isToday(vo.n("topic_time")))<font color="#FF0000">${page.day(vo.n("topic_time"))}</font>@else${page.day(vo.n("topic_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.s("topic_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("topic_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
