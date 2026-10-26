@import com.maccms.backend.admin.web.view.content.ContentListView
@import com.maccms.backend.content.application.view.AdminRow
@import java.util.List
@import java.util.Map
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<style>
    table {
        table-layout: fixed;
    }


    td {
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
    }
</style>
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("data")}">
                <input type="hidden" value="${page.p("select")}" name="select">
                <input type="hidden" value="${page.p("input")}" name="input">
                <div class="layui-input-inline w150">
                    <select name="type">
                        <option value="">${page.lang().get("select_type")}</option>
                        @template.admin.partials.typeOptions(types = page.types(), mid = 1, selected = page.raw("type"), form = false)
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
                    <select name="weekday">
                        <option value="">${page.lang().get("admin/vod/select_weekday")}</option>
                        @for(String vo2 : page.<List<String>>x("weekdays"))
                        <option value="${vo2}" selected="${page.pEq("weekday", vo2)}">${vo2}</option>
                        @endfor
                    </select>
                </div>

                <div class="layui-input-inline w150">
                    <select name="area">
                        <option value="">${page.lang().get("admin/vod/select_area")}</option>
                        @for(String vo2 : page.<List<String>>x("areas"))
                        <option value="${vo2}" selected="${page.pEq("area", vo2)}">${vo2}</option>
                        @endfor
                    </select>
                </div>

                <div class="layui-input-inline w150">
                    <select name="lang">
                        <option value="">${page.lang().get("admin/vod/select_lang")}</option>
                        @for(String vo2 : page.<List<String>>x("langs"))
                        <option value="${vo2}" selected="${page.pEq("lang", vo2)}">${vo2}</option>
                        @endfor
                    </select>
                </div>


                <div class="layui-input-inline w150">
                    <select name="server">
                        <option value="">${page.lang().get("admin/vod/select_server")}</option>
                        @for(Map<String, String> vo : page.<java.util.List<java.util.Map<String, String>>>x("serverList"))
                        <option value="${vo.get("from")}" selected="${page.pEq("server", String.valueOf(vo.get("from")))}">${vo.get("show")}</option>
                        @endfor
                    </select>
                </div>

                <div class="layui-input-inline w150">
                    <select name="player">
                        <option value="">${page.lang().get("admin/vod/select_player")}</option>
                        <option value="no" selected="${page.pEq("player", "no")}">${page.lang().get("admin/vod/player_empty")}</option>
                        @for(Map<String, String> vo : page.<java.util.List<java.util.Map<String, String>>>x("playerList"))
                        <option value="${vo.get("from")}" selected="${page.pEq("player", String.valueOf(vo.get("from")))}">${vo.get("show")}</option>
                        @endfor
                    </select>
                </div>

                <div class="layui-input-inline w150">
                    <select name="downer">
                        <option value="">${page.lang().get("admin/vod/select_downer")}</option>
                        <option value="no" selected="${page.pEq("downer", "no")}">${page.lang().get("admin/vod/downer_empty")}</option>
                        @for(Map<String, String> vo : page.<java.util.List<java.util.Map<String, String>>>x("downerList"))
                        <option value="${vo.get("from")}" selected="${page.pEq("downer", String.valueOf(vo.get("from")))}">${vo.get("show")}</option>
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
                    <select name="isend">
                        <option value="">${page.lang().get("admin/vod/select_isend")}</option>
                        <option value="0" selected="${page.pEq("isend", "0")}">${page.lang().get("admin/vod/no_end")}</option>
                        <option value="1" selected="${page.pEq("isend", "1")}">${page.lang().get("admin/vod/is_end")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="copyright">
                        <option value="">${page.lang().get("admin/vod/select_copyright")}</option>
                        <option value="0" selected="${page.pEq("copyright", "0")}">${page.lang().get("close")}</option>
                        <option value="1" selected="${page.pEq("copyright", "1")}">${page.lang().get("open")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="plot">
                        <option value="">${page.lang().get("admin/vod/select_plot")}</option>
                        <option value="0" selected="${page.pEq("plot", "0")}">${page.lang().get("admin/vod/no")}</option>
                        <option value="1" selected="${page.pEq("plot", "1")}">${page.lang().get("admin/vod/have")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="order">
                        <option value="">${page.lang().get("select_sort")}</option>
                        <option value="vod_time" selected="${page.pEq("order", "vod_time")}">${page.lang().get("update_time")}</option>
                        <option value="vod_id" selected="${page.pEq("order", "vod_id")}">${page.lang().get("id")}</option>
                        <option value="vod_hits" selected="${page.pEq("order", "vod_hits")}">${page.lang().get("hits")}</option>
                        <option value="vod_hits_month" selected="${page.pEq("order", "vod_hits_month")}">${page.lang().get("hits_month")}</option>
                        <option value="vod_hits_week" selected="${page.pEq("order", "vod_hits_week")}">${page.lang().get("hits_week")}</option>
                        <option value="vod_hits_day" selected="${page.pEq("order", "vod_hits_day")}">${page.lang().get("hits_day")}</option>
                    </select>
                </div>


                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <input type="hidden" name="repeat" value="${page.p("repeat")}" />
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" data-full="1" class="layui-btn layui-btn-primary j-iframe"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=type_id&tpl=select_type&url=vod/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("type")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_level&tpl=select_level&url=vod/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("level")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_hits&tpl=select_hits&url=vod/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("hits")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_status&tpl=select_status&url=vod/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_lock&tpl=select_lock&url=vod/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("lock")}</a>
            <a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_copyright&tpl=select_copyright&url=vod/field"}" data-width="270" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("admin/vod/copyright")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-href="${page.url().build("images/opt?tab=vod")}" href="javascript:;" title="${page.lang().get("pic_sync")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("pic_sync")}</a>
            <a class="layui-btn layui-btn-primary j-iframe" data-checkbox="true" data-href="${page.url().build("make/make?ac=info&tab=vod")}" href="javascript:;" title="${page.lang().get("make_page")}"><i class="layui-icon">&#xe620;</i>${page.lang().get("make_page")}</a>
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
                <th id="table_th_vod_name">${page.lang().get("name")}</th>
                <th width="50">${page.lang().get("hits")}</th>
                <th width="50">${page.lang().get("hits_week")}</th>
                <th width="40">${page.lang().get("score")}</th>
                <th width="30">${page.lang().get("level")}</th>
                <th width="30">${page.lang().get("browse")}</th>
                <th width="80">${page.lang().get("player")}</th>
                <th width="120">${page.lang().get("update_time")}</th>
                <th width="190">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("vod_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("vod_id")}</td>
                <td>
                    [${page.typeName(vo.n("type_id"))}] <a target="_blank" class="layui-badge-rim" href="${page.links().vod(vo)}">${page.xssText(vo.s("vod_name"))}</a>
                    @if(vo.n("vod_status") == 0) <span class="layui-badge">${page.lang().get("reviewed_not")}</span>@endif
                    @if(vo.n("vod_lock") == 1) <span class="layui-badge">${page.lang().get("lock")}</span>@endif
                    @if(vo.n("vod_isend") == 0 && !vo.s("vod_serial").isEmpty()) <span class="layui-badge layui-bg-blue">${page.lang().get("admin/vod/serialize")}${vo.s("vod_serial")}</span>@endif
                    @if(!vo.s("vod_remarks").isEmpty()) <span class="layui-badge layui-bg-orange">${vo.s("vod_remarks")}</span>@endif
                    @if(vo.n("vod_plot") == 1) <span class="layui-badge layui-bg-cyan">${page.lang().get("plot")}</span>@endif
                    @if(vo.n("vod_copyright") == 1) <span class="layui-badge layui-bg-black">${page.lang().get("admin/vod/copyright")}</span>@endif
                </td>
                <td>${vo.s("vod_hits")}</td>
                <td>${vo.s("vod_hits_week")}</td>
                <td>${vo.s("vod_score")}</td>
                <td><a data-href="${page.url().build("index/select") + "?tab=vod&col=vod_level&tpl=select_level&url=vod/field&ids=" + vo.s("vod_id")}" data-width="270" data-height="100" class=" j-select"><span class="layui-badge layui-bg-orange">${vo.s("vod_level")}</span></a></td>
                <td>@if(page.made(vo, "vod_time", "vod_time_make"))<a target="_blank" class="layui-badge layui-bg-green " href="${page.links().vod(vo)}">Y</a>@else<a class="layui-badge" href="${page.url().build("make/make?ac=info&tab=vod") + "?ids=" + vo.s("vod_id") + "&ref=1"}">N</a>@endif</td>
                <td><span title="${page.playGroups(vo)}">${page.playGroups(vo)}</span></td>
                <td>@if(page.isToday(vo.n("vod_time")))<font color="#FF0000">${page.day(vo.n("vod_time"))}</font>@else${page.day(vo.n("vod_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-full="1" data-href="${page.url().build("info?id=" + vo.s("vod_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("vod_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1"  data-href="${page.url().build("role/data?select=1&tab=vod&rid=" + vo.s("vod_id"))}" href="javascript:;" title="${page.lang().get("role")}">${page.lang().get("role")}</a>
                    <a class="layui-badge-rim j-iframe" data-full="1"  data-href="${page.url().build("iplot?id=" + vo.s("vod_id"))}" href="javascript:;" title="${page.lang().get("plot")}">${page.lang().get("plot")}</a>
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
<script type="text/javascript" src="${page.head().assetsPath()}/vod-index.js"></script>
`)
