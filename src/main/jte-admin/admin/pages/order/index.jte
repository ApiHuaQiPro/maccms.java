@import com.maccms.backend.admin.web.view.member.MemberListView
@import com.maccms.backend.content.application.view.AdminRow
@param MemberListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box" >
        <div class="center mb10">
            <form class="layui-form " method="post" action="${page.url().build("index")}">
                <div class="layui-input-inline w150">
                    <select name="status">
                        <option value="">${page.lang().get("select_order_status")}</option>
                        <option value="0" selected="${page.pEq("status", "0")}">${page.lang().get("not_paid")}</option>
                        <option value="1" selected="${page.pEq("status", "1")}">${page.lang().get("paid")}</option>
                    </select>
                </div>
                <div class="layui-input-inline">
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wdText()}">
                </div>
                <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
            </form>
        </div>

        <div class="layui-btn-group">
            <a data-href="${page.url().build("del")}" class="layui-btn layui-btn-primary j-page-btns confirm"><i class="layui-icon">&#xe640;</i>${page.lang().get("del")}</a>
            <a data-href="${page.url().build("del") + "?ids=1&all=1"}" class="layui-btn layui-btn-primary j-ajax" confirm="${page.lang().get("clear_confirm")}"><i class="layui-icon">&#xe640;</i>${page.lang().get("clear")}</a>
        </div>

    </div>

     <form class="layui-form " method="post" id="pageListForm">
         <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th width="50">${page.lang().get("id")}</th>
                <th width="100">${page.lang().get("admin/order/order_no")}</th>
                <th width="80">${page.lang().get("admin/order/order_money")}</th>
                <th width="80">${page.lang().get("admin/order/order_status")}</th>
                <th width="130">${page.lang().get("admin/order/order_time")}</th>
                <th width="100">${page.lang().get("admin/order/pay_type")}</th>
                <th width="130">${page.lang().get("admin/order/pay_time")}</th>
                <th width="80">${page.lang().get("user")}</th>
                <th width="50">${page.lang().get("opt")}</th>
            </tr>
            </thead>
            <tbody>
            @for(AdminRow vo : page.rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s("order_id")}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s("order_id")}</td>
                <td>${page.text(vo.s("order_code"))}</td>
                <td>${vo.s("order_price")}</td>
                <td>${page.orderStatus(vo.n("order_status"))}</td>
                <td>@if(page.isToday(vo.n("order_time")))<font color="#FF0000">${page.day(vo.n("order_time"))}</font>@else${page.day(vo.n("order_time"))}@endif</td>
                <td></td>
                <td></td>
                <td>${vo.s("user_id")}、${page.text(vo.s("user_name"))}</td>
                <td>
                    <a class="layui-badge-rim j-tr-del" data-href="${page.url().build("del?ids=" + vo.s("order_id"))}" href="javascript:;" title="${page.lang().get("del")}">${page.lang().get("del")}</a>
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
