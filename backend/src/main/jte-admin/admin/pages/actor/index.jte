@import com.maccms.backend.admin.web.view.content.ContentListView
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
                        @template.admin.partials.typeOptions(types = page.types(), mid = 8, selected = page.raw("type"), form = false)
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="status">
                        <option value="">${page.lang().get("select_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("reviewed")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("reviewed_not")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="level">
                        <option value="">${page.lang().get("select_level")}</option>
                        <option value="9" selected="${page.pEq("level", "9")}">${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                        @for(int i = 1; i <= 8; i++)
                        <option value="${i}" selected="${page.pEq("level", String.valueOf(i))}">${page.lang().get("level")}${i}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="pic">
                        <option value="">${page.lang().get("select_pic")}</option>
                        <option value="1" selected="${page.pEq("pic", "1")}">${page.lang().get("pic_empty")}</option>
                        <option value="2" selected="${page.pEq("pic", "2")}">${page.lang().get("pic_remote")}</option>
                        <option value="3" selected="${page.pEq("pic", "3")}">${page.lang().get("pic_sync_err")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="order">
                        <option value="">${page.lang().get("select_sort")}</option>
                        <option value="actor_time" selected="${page.pEq("order", "actor_time")}">${page.lang().get("update_time")}</option>
                        <option value="actor_id" selected="${page.pEq("order", "actor_id")}">${page.lang().get("id")}</option>
                        <option value="actor_hits" selected="${page.pEq("order", "actor_hits")}">${page.lang().get("hits")}</option>
                        <option value="actor_hits_month" selected="${page.pEq("order", "actor_hits_month")}">${page.lang().get("hits_month")}</option>
                        <option value="actor_hits_week" selected="${page.pEq("order", "actor_hits_week")}">${page.lang().get("hits_week")}</option>
                        <option value="actor_hits_day" selected="${page.pEq("order", "actor_hits_day")}">${page.lang().get("hits_day")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>
        @endif
        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" data-full="1" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=actor&col=type_id&tpl=select_type&url=actor/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("type")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=actor&col=actor_level&tpl=select_level&url=actor/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("level")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=actor&col=actor_hits&tpl=select_hits&url=actor/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("hits")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=actor&col=actor_status&tpl=select_status&url=actor/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("images/opt?tab=actor")}" href="javascript:;" title="${page.lang().get("pic_sync")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("pic_sync")}</a>
        </div>

    </div>

    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="50">${page.lang().get("id")}</th>
                <th >${page.lang().get("actor")}</th>
                <th width="150">${page.lang().get("area")}</th>
                <th width="150">${page.lang().get("sex")}</th>
                <th width="40">${page.lang().get("level")}</th>
                <th width="40">${page.lang().get("height")}</th>
                <th width="40">${page.lang().get("weight")}</th>
                <th width="120">${page.lang().get("update_time")}</th>
                <th width="80">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("actor_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("actor_id")}</td>
                <td>[${page.typeName(vo.n("type_id"))}] <a target="_blank" class="layui-badge-rim " href="${page.links().actor(vo)}">${vo.s("actor_name")}</a> @if(vo.n("actor_status") == 0) <span class="layui-badge">${page.lang().get("reviewed_not")}</span>@endif @if(vo.n("actor_lock") == 1) <span class="layui-badge">${page.lang().get("lock")}</span>@endif</td>
                <td>${vo.s("actor_area")}</td>
                <td>${vo.s("actor_sex")}</td>
                <td><a data-href="${page.url().build("index/select") + "?tab=actor&col=actor_level&tpl=select_level&url=actor/field&ids=" + vo.s("actor_id")}" data-width="270" data-height="100" class=" j-select"><span class="layui-badge layui-bg-orange">${vo.s("actor_level")}</span></a></td>
                <td>${vo.s("actor_height")}</td>
                <td>${vo.s("actor_weight")}</td>
                <td>@if(page.isToday(vo.n("actor_time")))<font color="#FF0000">${page.day(vo.n("actor_time"))}</font>@else${page.day(vo.n("actor_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.s("actor_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("actor_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
