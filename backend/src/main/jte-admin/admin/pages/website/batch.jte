@import com.maccms.backend.admin.web.view.content.ContentListView
@param ContentListView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <form class="layui-form" method="post" action="">

        <div class="my-toolbar-box">

            <div class="center mb10">

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
                            <option value="1" selected="${page.pEq("level", "1")}">${page.lang().get("level")}1</option>
                            <option value="2" selected="${page.pEq("level", "2")}">${page.lang().get("level")}2</option>
                            <option value="3" selected="${page.pEq("level", "3")}">${page.lang().get("level")}3</option>
                            <option value="4" selected="${page.pEq("level", "4")}">${page.lang().get("level")}4</option>
                            <option value="5" selected="${page.pEq("level", "5")}">${page.lang().get("level")}5</option>
                            <option value="6" selected="${page.pEq("level", "6")}">${page.lang().get("level")}6</option>
                            <option value="7" selected="${page.pEq("level", "7")}">${page.lang().get("level")}7</option>
                            <option value="8" selected="${page.pEq("level", "8")}">${page.lang().get("level")}8</option>
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

                    <div class="layui-input-inline">
                        <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.wd()}">
                    </div>

            </div>

        </div>

        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("del_multi")}</legend>
            <div class="layui-field-box">
                <div class="layui-form-item">
                    <div class="layui-inline">
                        <label class="layui-form-label"><input type="checkbox" lay-ignore value="1" name="ck_del">${page.lang().get("del_data")}</label>
                        <div class="layui-input-inline" style="width: 100px;">
                        </div>
                    </div>
                </div>
                <div class="layui-form-item">
                    <button type="button" class="layui-btn btn_submit">${page.lang().get("del_multi")}</button>
                </div>
            </div>
        </fieldset>

        <fieldset class="layui-elem-field">
        <legend>${page.lang().get("multi_set")}</legend>
        <div class="layui-field-box">

            <div class="layui-form-item">
                <div class="layui-inline">
                    <label class="layui-form-label"><input type="checkbox" lay-ignore value="1" name="ck_level" title="${page.lang().get("level")}">${page.lang().get("level")}</label>
                    <div class="layui-input-inline" style="width: 100px;">
                        <select name="val_level">
                            <option value="">${page.lang().get("select_level")}</option>
                            <option value="9" >${page.lang().get("level")}9-${page.lang().get("slide")}</option>
                            <option value="1" >${page.lang().get("level")}1</option>
                            <option value="2" >${page.lang().get("level")}2</option>
                            <option value="3" >${page.lang().get("level")}3</option>
                            <option value="4" >${page.lang().get("level")}4</option>
                            <option value="5" >${page.lang().get("level")}5</option>
                            <option value="6" >${page.lang().get("level")}6</option>
                            <option value="7" >${page.lang().get("level")}7</option>
                            <option value="8" >${page.lang().get("level")}8</option>
                        </select>
                    </div>
                </div>
            </div>

            <div class="layui-form-item">
                <div class="layui-inline">
                    <label class="layui-form-label"><input type="checkbox" lay-ignore value="1" name="ck_lock">${page.lang().get("lock")}</label>
                    <div class="layui-input-inline" style="width: 100px;">
                        <select name="val_lock">
                            <option value="">${page.lang().get("select_opt")}</option>
                            <option value="0" >${page.lang().get("unlock")}</option>
                            <option value="1" >${page.lang().get("lock")}</option>
                        </select>
                    </div>
                </div>
            </div>

            <div class="layui-form-item">
                <div class="layui-inline">
                    <label class="layui-form-label"><input type="checkbox" lay-ignore value="1" name="ck_status">${page.lang().get("status")}</label>
                    <div class="layui-input-inline" style="width: 100px;">
                        <select name="val_status">
                            <option value="">${page.lang().get("select_status")}</option>
                            <option value="0" >${page.lang().get("reviewed_not")}</option>
                            <option value="1" >${page.lang().get("reviewed")}</option>
                        </select>
                    </div>
                </div>
            </div>

            <div class="layui-form-item">
                <div class="layui-inline">
                    <label class="layui-form-label"><input type="checkbox" lay-ignore value="1" name="ck_hits">${page.lang().get("hits")}</label>
                    <div class="layui-input-inline" style="width: 100px;">
                        <input type="text" name="val_hits_min" required  placeholder="${page.lang().get("min_val")}" autocomplete="off" class="layui-input">
                    </div>
                    <div class="layui-input-inline" style="width: 100px;">
                        <input type="text" name="val_hits_max" required  placeholder="${page.lang().get("max_val")}" autocomplete="off" class="layui-input">
                    </div>
                </div>
            </div>

            <div class="layui-form-item">
                <button type="submit" class="layui-btn btn_submit">${page.lang().get("start_exec")}</button>
            </div>

        </div>
    </fieldset>
    </form>
</div>


`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/batch-form.js"></script>
`)
