@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >
        <div class="center mb10">
            <form class="layui-form " method="post"  id="searchForm" action="${page.url().build("index")}">
                <div class="layui-input-inline w150">
                    <select name="sale_status">
                        <option value="">${page.lang().get("select_sale_status")}</option>
                        <option value="0" selected="${page.pEq("sale_status", "0")}">${page.lang().get("not_sale")}</option>
                        <option value="1" selected="${page.pEq("sale_status", "1")}">${page.lang().get("sold")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="use_status">
                        <option value="">${page.lang().get("select_use_status")}</option>
                        <option value="0" selected="${page.pEq("use_status", "0")}">${page.lang().get("not_used")}</option>
                        <option value="1" selected="${page.pEq("use_status", "1")}">${page.lang().get("used")}</option>
                    </select>
                </div>
                <div class="layui-input-inline w150">
                    <select name="time">
                        <option value="">${page.lang().get("select_time")}</option>
                        <option value="1" selected="${page.pEq("time", "1")}">${page.lang().get("the_last_time")}</option>
                        <option value="0" selected="${page.pEq("time", "0")}">${page.lang().get("that_day")}</option>
                        <option value="7" selected="${page.pEq("time", "7")}">${page.lang().get("in_a_week")}</option>
                        <option value="30" selected="${page.pEq("time", "30")}">${page.lang().get("in_a_month")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
                <button class="layui-btn mgl-20" type="button" id="btnExport">${page.lang().get("export")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("info")}" class="layui-btn layui-btn-primary j-iframe" data-width="600px" data-height="400px"><i class="layui-icon">&#xe654;</i>${page.lang().get("add")}</a>
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("del") + "?ids=1&all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>

        </div>
    </div>

     <form class="layui-form " method="post" id="pageListForm">
         <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="80">${page.lang().get("id")}</th>
                <th width="150">${page.lang().get("card_no")}</th>
                <th width="100">${page.lang().get("pass")}</th>
                <th width="100">${page.lang().get("money")}</th>
                <th width="100">${page.lang().get("points")}</th>
                <th width="100">${page.lang().get("add_time")}</th>
                <th width="100">${page.lang().get("user")}</th>
                <th width="150">${page.lang().get("use_time")}</th>
                <th width="50">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("card_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("card_id")}</td>
                <td>${vo.s("card_no")}</td>
                <td>${vo.s("card_pwd")}</td>
                <td>${vo.s("card_money")}</td>
                <td>${vo.s("card_points")}</td>
                <td>@if(page.isToday(vo.n("card_add_time")))<font color="#FF0000">${page.day(vo.n("card_add_time"))}</font>@else${page.day(vo.n("card_add_time"))}@endif</td>
                <td>${vo.s("user_id")}、${page.text(vo.s("user_name"))}</td>
                <td>@if(vo.n("card_use_time") == 0) @elseif(page.isToday(vo.n("card_use_time")))<font color="#FF0000">${page.day(vo.n("card_use_time"))}</font>@else${page.day(vo.n("card_use_time"))}@endif</td>
                <td>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("card_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
                </td>
            </tr>
            @endfor
            </tbody>
        </table>

        <div id="pages" class="center"></div>

    </form>
    <iframe id="if" width="0" height="0"></iframe>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/admin-list.js" data-cur-url="${page.curUrl()}" data-count="${page.list().total()}" data-limit="${page.list().limit()}" data-curr="${page.list().page()}"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/card-index.js" data-export-url="${page.url().build("card/index")}"></script>
`)
