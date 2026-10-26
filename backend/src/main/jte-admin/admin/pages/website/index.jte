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
                        @template.admin.partials.typeOptions(types = page.types(), mid = 11, selected = page.raw("type"), form = false)
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
                        <option value="website_time" selected="${page.pEq("order", "website_time")}">${page.lang().get("update_time")}</option>
                        <option value="website_id" selected="${page.pEq("order", "website_id")}">${page.lang().get("id")}</option>
                        <option value="website_hits" selected="${page.pEq("order", "website_hits")}">${page.lang().get("hits")}</option>
                        <option value="website_hits_month" selected="${page.pEq("order", "website_hits_month")}">${page.lang().get("hits_month")}</option>
                        <option value="website_hits_week" selected="${page.pEq("order", "website_hits_week")}">${page.lang().get("hits_week")}</option>
                        <option value="website_hits_day" selected="${page.pEq("order", "website_hits_day")}">${page.lang().get("hits_day")}</option>
                    </select>
                </div>

                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" data-full="1" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=website&col=type_id&tpl=select_type&url=website/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("type")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=website&col=website_level&tpl=select_level&url=website/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("level")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=website&col=website_hits&tpl=select_hits&url=website/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("hits")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=website&col=website_status&tpl=select_status&url=website/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=website&col=website_lock&tpl=select_lock&url=website/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("lock")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("images/opt?tab=website")}" href="javascript:;" title="${page.lang().get("pic_sync")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("pic_sync")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-checkbox="true" data-href="${page.url().build("make/make?ac=info&tab=website")}" href="javascript:;" title="${page.lang().get("make_page")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("make_page")}</a>
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
                <th width="50">${page.lang().get("id")}</th>
                <th >${page.lang().get("name")}</th>
                <th width="350">${page.lang().get("url")}</th>
                <th width="50">${page.lang().get("hits")}</th>
                <th width="50">${page.lang().get("referer")}</th>
                <th width="30">${page.lang().get("level")}</th>
                <th width="30">${page.lang().get("browse")}</th>
                <th width="120">${page.lang().get("update_time")}</th>
                <th width="170">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("website_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("website_id")}</td>
                <td>[${page.typeName(vo.n("type_id"))}] <a target="_blank" class="layui-badge-rim " href="${page.links().website(vo)}">${vo.s("website_name")}</a> @if(vo.n("website_status") == 0) <span class="layui-badge">${page.lang().get("reviewed_not")}</span>@endif @if(vo.n("website_lock") == 1) <span class="layui-badge">${page.lang().get("lock")}</span>@endif</td>
                <td><a class="layui-badge-rim " href="${page.outLink(vo.s("website_jumpurl"))}" target="_blank">${vo.s("website_jumpurl")}</a></td>
                <td>${vo.s("website_hits")}</td>
                <td>${vo.s("website_referer")}</td>
                <td><a data-href="${page.url().build("index/select") + "?tab=website&col=website_level&tpl=select_level&url=website/field&ids=" + vo.s("website_id")}" data-width="270" data-height="100" class=" j-select"><span class="layui-badge layui-bg-orange">${vo.s("website_level")}</span></a></td>
                <td>@if(page.made(vo, "website_time", "website_time_make"))<a target="_blank" class="layui-badge layui-bg-green " href="${page.links().website(vo)}">Y</a>@else<a class="layui-badge" href="${page.url().build("make/make?ac=info&tab=website") + "?ids=" + vo.s("website_id") + "&ref=1"}">N</a>@endif</td>
                <td>@if(page.isToday(vo.n("website_time")))<font color="#FF0000">${page.day(vo.n("website_time"))}</font>@else${page.day(vo.n("website_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("visit/index?mid=11") + "?wd=" + page.host(vo.s("website_jumpurl"))}" href="javascript:;" title="">${page.lang().get("referer")}</a>
                    <a class="layui-badge-rim j-ajax" data-href="${page.url().build("index/check_back_link") + "?url=" + vo.s("website_jumpurl")}" refresh="no" href="javascript:;" title="${page.lang().get("detect")}">${page.lang().get("detect")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.s("website_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("website_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
