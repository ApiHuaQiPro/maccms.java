@import com.maccms.backend.admin.web.view.content.ContentListView
@import com.maccms.backend.content.application.view.AdminRow
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <input type="hidden" value="${page.p("select")}" name="select">
                <input type="hidden" value="${page.p("input")}" name="input">
                <div class="layui-input-inline w150">
                    <select name="type">
                        <option value="">${page.lang().get("select_type")}</option>
                        @template.admin.partials.typeOptions(types = page.types(), mid = 2, selected = page.raw("type"), form = false)
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="status">
                        <option value="">${page.lang().get("select_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("reviewed_not")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("reviewed")}</option>
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
                    <select name="lock">
                        <option value="">${page.lang().get("select_lock")}</option>
                        <option value="0" selected="${page.pEq("lock", "0")}">${page.lang().get("unlock")}</option>
                        <option value="1" selected="${page.pEq("lock", "1")}">${page.lang().get("lock")}</option>
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
                        <option value="art_time" selected="${page.pEq("order", "art_time")}">${page.lang().get("update_time")}</option>
                        <option value="art_id" selected="${page.pEq("order", "art_id")}">${page.lang().get("id")}</option>
                        <option value="art_hits" selected="${page.pEq("order", "art_hits")}">${page.lang().get("hits")}</option>
                        <option value="art_hits_month" selected="${page.pEq("order", "art_hits_month")}">${page.lang().get("hits_month")}</option>
                        <option value="art_hits_week" selected="${page.pEq("order", "art_hits_week")}">${page.lang().get("hits_week")}</option>
                        <option value="art_hits_day" selected="${page.pEq("order", "art_hits_day")}">${page.lang().get("hits_day")}</option>
                    </select>
                </div>

                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" data-full="1" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=art&col=type_id&tpl=select_type&url=art/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("type")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=art&col=art_level&tpl=select_level&url=art/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("level")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=art&col=art_hits&tpl=select_hits&url=art/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("hits")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=art&col=art_status&tpl=select_status&url=art/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=art&col=art_lock&tpl=select_lock&url=art/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("lock")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("images/opt?tab=art")}" href="javascript:;" title="${page.lang().get("pic_sync")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("pic_sync")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-checkbox="true" data-href="${page.url().build("make/make?ac=info&tab=art")}" href="javascript:;" title="${page.lang().get("make_page")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("make_page")}</a>
            @if(page.pEq("select", "1"))
            <a data-href="" data-input="${page.p("input")}" class="layui-btn layui-btn-normal">${page.lang().get("select_return")}</a>
            @endif
            @if(!page.p("repeat").isEmpty())
            <a data-href="${page.url().build("del") + "?repeat=1&retain=min"}" data-checkbox="no" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del_auto_keep_min")}</a>
            <a data-href="${page.url().build("del") + "?repeat=1&retain=max"}" data-checkbox="no" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del_auto_keep_max")}</a>
            @endif
        </div>

    </div>


    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="40">${page.lang().get("id")}</th>
                <th >${page.lang().get("name")}</th>
                <th width="50">${page.lang().get("hits")}</th>
                <th width="30">${page.lang().get("score")}</th>
                <th width="30">${page.lang().get("level")}</th>
                <th width="30">${page.lang().get("browse")}</th>
                <th width="80">${page.lang().get("author")}</th>
                <th width="120">${page.lang().get("update_time")}</th>
                <th width="80">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("art_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("art_id")}</td>
                <td>[${page.xssText(page.typeName(vo.n("type_id")))}] <a target="_blank" class="layui-badge-rim " href="${page.links().art(vo)}">${page.xssText(vo.s("art_name"))}</a> @if(vo.n("art_status") == 0) <span class="layui-badge">${page.lang().get("reviewed_not")}</span>@endif @if(vo.n("art_lock") == 1) <span class="layui-badge">${page.lang().get("lock")}</span>@endif</td>
                <td>${vo.s("art_hits")}</td>
                <td>${vo.s("art_score")}</td>
                <td><a data-href="${page.url().build("index/select") + "?tab=art&col=art_level&tpl=select_level&url=art/field&ids=" + vo.s("art_id")}" data-width="270" data-height="100" class=" j-select"><span class="layui-badge layui-bg-orange">${vo.s("art_level")}</span></a></td>
                <td>@if(page.made(vo, "art_time", "art_time_make"))<a target="_blank" class="layui-badge layui-bg-green " href="${page.links().art(vo)}">Y</a>@else<a class="layui-badge" href="${page.url().build("make/make?ac=info&tab=art") + "?ids=" + vo.s("art_id") + "&ref=1"}">N</a>@endif</td>
                <td>${vo.s("art_author")}</td>
                <td>@if(page.isToday(vo.n("art_time")))<font color="#FF0000">${page.day(vo.n("art_time"))}</font>@else${page.day(vo.n("art_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.s("art_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("art_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
<script type="text/javascript" src="${page.head().assetsPath()}/select-return.js"></script>
`)
