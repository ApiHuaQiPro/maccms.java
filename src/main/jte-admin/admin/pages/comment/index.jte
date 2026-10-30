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
                    <select name="mid">
                        <option value="">${page.lang().get("select_model")}</option>
                        <option value="1" selected="${page.pEq("mid", "1")}">${page.lang().get("vod")}</option>
                        <option value="2" selected="${page.pEq("mid", "2")}">${page.lang().get("art")}</option>
                        <option value="3" selected="${page.pEq("mid", "3")}">${page.lang().get("topic")}</option>
                        <option value="8" selected="${page.pEq("mid", "8")}">${page.lang().get("actor")}</option>
                        <option value="9" selected="${page.pEq("mid", "9")}">${page.lang().get("role")}</option>
                        <option value="11" selected="${page.pEq("mid", "11")}">${page.lang().get("website")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w100">
                    <select name="report">
                        <option value="">${page.lang().get("select_report")}</option>
                        <option value="1" selected="${page.pEq("report", "1")}">${page.lang().get("report_not")}</option>
                        <option value="2" selected="${page.pEq("report", "2")}">${page.lang().get("report_yes")}</option>
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
            <a data-href="${page.url().build("index/select") + "?tab=comment&col=comment_status&tpl=select_status&url=comment/field"}" data-width="470" data-height="100" data-checkbox="1" class="layui-btn layui-btn-primary j-select"><i class="layui-icon">&#xe620;</i>${page.lang().get("status")}</a>
            <a data-href="${page.url().build("del") + "?all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>
        </div>
    </div>

    <form class="layui-form" method="post" id="pageListForm" >
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="60">${page.lang().get("id")}</th>
                <th width="60">${page.lang().get("model")}</th>
                <th width="60">${page.lang().get("status")}</th>
                <th >${page.lang().get("content")}</th>
                <th width="100">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("comment_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("comment_id")}</td>
                <td>${page.midText(vo.n("comment_mid"))}</td>
                <td>@if(vo.n("comment_status") == 0)<span class="layui-badge">${page.lang().get("reviewed_not")}</span>@else<span class="layui-badge layui-bg-green">${page.lang().get("reviewed")}</span>@endif</td>
                <td>
                    <div class="c-999 f-12">
                        <u style="cursor:pointer" class="text-primary">${vo.s("comment_name")}：</u>
                        <time>【@if(page.isToday(vo.n("comment_time")))<font color="#FF0000">${page.day(vo.n("comment_time"))}</font>@else${page.day(vo.n("comment_time"))}@endif】</time>
                        <span class="ml-20">ip：【${ContentListView.ip(vo.n("comment_ip"))}】</span>
                        <span class="ml-20">${page.lang().get("up")}：【${vo.s("comment_up")}】</span>
                        <span class="ml-20">${page.lang().get("hate")}：【${vo.s("comment_down")}】</span>
                        <span class="ml-20">${page.lang().get("report")}：【${vo.s("comment_report")}】</span>
                        <span class="ml-20">${page.lang().get("link")}：
                            @if(page.related(vo) == null && java.util.List.of(1L, 2L, 3L, 8L, 9L, 11L).contains(vo.n("comment_mid")))
                            【${page.lang().get("del_data")}】
                            @elseif(vo.n("comment_mid") == 1)
                            【<a target="_blank" href="${page.links().vod(page.related(vo))}">${page.related(vo).s("vod_name")}</a>】
                            @elseif(vo.n("comment_mid") == 2)
                            【<a target="_blank" href="${page.links().art(page.related(vo))}">${page.related(vo).s("art_name")}</a>】
                            @elseif(vo.n("comment_mid") == 3)
                            【<a target="_blank" href="${page.links().topic(page.related(vo))}">${page.related(vo).s("topic_name")}</a>】
                            @elseif(vo.n("comment_mid") == 8)
                            【<a target="_blank" href="${page.links().actor(page.related(vo))}">${page.related(vo).s("actor_name")}</a>】
                            @elseif(vo.n("comment_mid") == 9)
                            【<a target="_blank" href="${page.links().role(page.related(vo))}">${page.related(vo).s("role_name")}</a>】
                            @endif
                        </span>
                    </div>
                    <div class="f-12 c-999">
                        ${page.lang().get("comment")}：${page.restored(vo.s("comment_content"))}
                    </div>
                </td>
                <td>
                    <a class="layui-badge-rim j-iframe" data-href="${page.url().build("info?id=" + vo.s("comment_id"))}" href="javascript:;" title="${page.lang().get("edit")}">${page.lang().get("edit")}</a>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("comment_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
