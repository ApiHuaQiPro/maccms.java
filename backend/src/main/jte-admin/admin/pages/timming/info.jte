@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("status")}：</label>
                        <div class="layui-input-inline">
                            <input name="status" type="radio" id="rad-1" value="0" title="${page.lang().get("disable")}" checked="${!page.eq(page.at("info.status"), 1L)}">
                            <input name="status" type="radio" id="rad-2" value="1" title="${page.lang().get("enable")}" checked="${page.eq(page.at("info.status"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("name")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v(page.at("info.name"))}" placeholder="${page.lang().get("admin/timming/unique_id")}" id="name" name="name"  readonly="${!page.eq(page.at("info.name"), "")}">
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/timming/call_method")}/api.php/timming/index?name=${page.t(page.at("info.name"))}</div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("remarks")}：</label>
                        <div class="layui-input-inline w500">
                            <input type="text" class="layui-input" value="${page.v(page.at("info.des"))}" placeholder="" id="des" name="des">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/timming/exec_file")}：</label>
                        <div class="layui-input-inline w500">
                            <select class="" name="file">
                                <option value="collect" selected="${page.eq(page.at("info.file"), "collect")}">${page.lang().get("admin/timming/collect")}</option>
                                <option value="make" selected="${page.eq(page.at("info.file"), "make")}">${page.lang().get("admin/timming/make")}</option>
                                <option value="cj" selected="${page.eq(page.at("info.file"), "cj")}">${page.lang().get("admin/timming/cj")}</option>
                                <option value="cache" selected="${page.eq(page.at("info.file"), "cache")}">${page.lang().get("admin/timming/cache")}</option>
                                <option value="urlsend" selected="${page.eq(page.at("info.file"), "urlsend")}">${page.lang().get("admin/timming/urlsend")}</option>
                            </select>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/timming/attach_param")}：</label>
                        <div class="layui-input-block">
                            <input type="text" class="layui-input" value="${page.v(page.at("info.param"))}" placeholder="${page.lang().get("admin/timming/attach_param_tip")}" id="param" name="param">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/timming/exec_cycle")}：</label>
                        <div class="layui-input-block role-list-form">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="1" title="${page.lang().get("monday")}" checked="${page.has(page.at("info.weeks"), "1")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="2" title="${page.lang().get("tuesday")}" checked="${page.has(page.at("info.weeks"), "2")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="3" title="${page.lang().get("wednesday")}" checked="${page.has(page.at("info.weeks"), "3")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="4" title="${page.lang().get("thursday")}" checked="${page.has(page.at("info.weeks"), "4")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="5" title="${page.lang().get("friday")}" checked="${page.has(page.at("info.weeks"), "5")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="6" title="${page.lang().get("saturday")}" checked="${page.has(page.at("info.weeks"), "6")}">
                            <input type="checkbox" name="weeks[]" class="layui-checkbox" lay-skin="primary" value="0" title="${page.lang().get("sunday")}" checked="${page.has(page.at("info.weeks"), "0")}">

                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/timming/exec_time")}：</label>
                        <div class="layui-input-block role-list-form">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="00" title="00" checked="${page.has(page.at("info.hours"), "00")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="01" title="01" checked="${page.has(page.at("info.hours"), "01")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="02" title="02" checked="${page.has(page.at("info.hours"), "02")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="03" title="03" checked="${page.has(page.at("info.hours"), "03")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="04" title="04" checked="${page.has(page.at("info.hours"), "04")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="05" title="05" checked="${page.has(page.at("info.hours"), "05")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="06" title="06" checked="${page.has(page.at("info.hours"), "06")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="07" title="07" checked="${page.has(page.at("info.hours"), "07")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="08" title="08" checked="${page.has(page.at("info.hours"), "08")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="09" title="09" checked="${page.has(page.at("info.hours"), "09")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="10" title="10" checked="${page.has(page.at("info.hours"), "10")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="11" title="11" checked="${page.has(page.at("info.hours"), "11")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="12" title="12" checked="${page.has(page.at("info.hours"), "12")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="13" title="13" checked="${page.has(page.at("info.hours"), "13")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="14" title="14" checked="${page.has(page.at("info.hours"), "14")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="15" title="15" checked="${page.has(page.at("info.hours"), "15")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="16" title="16" checked="${page.has(page.at("info.hours"), "16")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="17" title="17" checked="${page.has(page.at("info.hours"), "17")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="18" title="18" checked="${page.has(page.at("info.hours"), "18")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="19" title="19" checked="${page.has(page.at("info.hours"), "19")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="20" title="20" checked="${page.has(page.at("info.hours"), "20")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="21" title="21" checked="${page.has(page.at("info.hours"), "21")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="22" title="22" checked="${page.has(page.at("info.hours"), "22")}">
                            <input type="checkbox" name="hours[]" class="layui-checkbox" lay-skin="primary" value="23" title="23" checked="${page.has(page.at("info.hours"), "23")}">
                        </div>
                    </div>


        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="button" class="layui-btn layui-btn-normal formCheckAll" lay-filter="formCheckAll" >${page.lang().get("check_all")}</button>
                <button type="button" class="layui-btn layui-btn-normal formCheckOther" lay-filter="formCheckOther">${page.lang().get("check_other")}</button>

                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/timming-info.js" data-msg-name-empty="${page.lang().get("name_empty")}"></script>
`)
