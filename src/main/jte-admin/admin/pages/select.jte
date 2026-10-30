@import com.maccms.backend.admin.web.view.content.SelectView
@import com.maccms.backend.content.application.view.AdminTypeNode
@param SelectView page
@if(page.tpl().equals("select_type"))<form class="layui-form m10" method="post" action="${page.action()}">
    <input type="hidden" name="col" value="${page.col()}">
    <input type="hidden" name="ids" value="${page.ids()}">

    <div class="layui-input-inline w150">
        <select name="val">
            <option value="">${page.lang().get("select_type")}</option>
            @for(AdminTypeNode vo : page.types())
            @if(vo.row().n("type_mid") == page.mid())
            <option value="${vo.row().s("type_id")}" >${vo.row().s("type_name")}</option>
            @for(AdminTypeNode ch : vo.children())
            <option value="${ch.row().s("type_id")}" selected="${page.selected(ch)}">&nbsp;&nbsp;&nbsp;&nbsp;├&nbsp;${ch.row().s("type_name")}</option>
            @endfor
            @endif
            @endfor
        </select>
    </div>
    <div class="layui-input-inline">
        <button type="submit" class="layui-btn" lay-submit="" refresh="${page.refresh()}" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
    </div>
</form>@elseif(page.tpl().equals("select_level"))<form class="layui-form m10" method="post" action="${page.action()}">
    <input type="hidden" name="col" value="${page.col()}">
    <input type="hidden" name="ids" value="${page.ids()}">

    <div class="layui-input-inline w150">
        <select name="val">
            <option value="">${page.lang().get("select_level")}</option>
            <option value="0">${page.lang().get("cancel_level")}</option>
            @for(int vo = 1; vo <= 9; vo++)
            <option value="${vo}">${page.lang().get("level")}${vo}</option>
            @endfor
        </select>
    </div>
    <div class="layui-input-inline">
        <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
    </div>
</form>@elseif(page.tpl().equals("select_hits"))<form class="layui-form m10" method="post" action="${page.action()}">
    <input type="hidden" name="col" value="${page.col()}">
    <input type="hidden" name="ids" value="${page.ids()}">

    <div class="layui-form-item">
        <label class="layui-form-label w50">${page.lang().get("start")}：</label>
        <div class="layui-input-inline w70">
            <input type="text" class="layui-input" value="" placeholder="" name="start">
        </div>
        <label class="layui-form-label w50">${page.lang().get("end")}：</label>
        <div class="layui-input-inline w70">
            <input type="text" class="layui-input" value="" placeholder="" name="end">
        </div>
        <div class="layui-input-inline w70">
            <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
        </div>
    </div>

</form>@else<form class="layui-form m10" method="post" action="${page.action()}">
    <input type="hidden" name="col" value="${page.col()}">
    <input type="hidden" name="ids" value="${page.ids()}">

    <div class="layui-input-inline w150">
        <select name="val">
            <option value="">${page.lang().get("select_opt")}</option>
            @if(page.tpl().equals("select_status"))
            <option value="0" >${page.lang().get("reviewed_not")}</option>
            <option value="1" >${page.lang().get("reviewed")}</option>
            @elseif(page.tpl().equals("select_lock"))
            <option value="0" >${page.lang().get("unlock")}</option>
            <option value="1" >${page.lang().get("lock")}</option>
            @elseif(page.tpl().equals("select_state"))
            <option value="0" >${page.lang().get("disable")}</option>
            <option value="1" >${page.lang().get("enable")}</option>
            @else
            <option value="0" >${page.lang().get("admin/vod/copyright_close")}</option>
            <option value="1" >${page.lang().get("admin/vod/copyright_open")}</option>
            @endif
        </select>
    </div>
    <div class="layui-input-inline">
        <button type="submit" class="layui-btn" lay-submit="" refresh="${page.refresh()}" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
    </div>
</form>@endif
