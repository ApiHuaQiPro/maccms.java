@import com.maccms.backend.install.web.view.Step3Page
@param Step3Page page
@template.install.layout.page(lang = page.lang(), staticPath = page.staticPath(), styles = @`
<style type="text/css">
.layui-table td, .layui-table th{text-align:left;}
.layui-table tbody tr.no{background-color:#f00;color:#fff;}
</style>
`, content = @`
<div class="install-box">
    <fieldset class="layui-elem-field layui-field-title">
        <legend>${page.lang().get("install/database_config")}</legend>
    </fieldset>
    <form class="layui-form layui-form-pane" action="?step=4" method="post">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/server_address")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="hostname" lay-verify="title" value="127.0.0.1">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/server_address_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/database_port")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="hostport" lay-verify="title" value="3306">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/database_port_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/database_name")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="database" lay-verify="title">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/database_name_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/database_username")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="username" lay-verify="title">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/database_username_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/database_pass")}</label>
            <div class="layui-input-inline w200">
                <input type="password" class="layui-input" name="password" lay-verify="title">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/database_pass_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/database_pre")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="prefix" lay-verify="title" value="mac_">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/database_pre_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/overwrite_database")}</label>
            <div class="layui-input-inline w200">
                <input type="radio" name="cover" value="1" title="${page.lang().get("install/overwrite")}">
                <input type="radio" name="cover" value="0" title="${page.lang().get("install/not_overwrite")}" checked>
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/overwrite_tip")}</div>
        </div>
        <div class="layui-form-item">
            <button type="submit" class="layui-btn fl" style="margin-left:120px;" lay-submit="" lay-filter="formTest">${page.lang().get("install/test_connect")}</button>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/test_connect_tip")}</div>
        </div>
    </form>
    <form class="layui-form layui-form-pane" action="?step=5" method="post">
        <input type="hidden" name="install_dir" value="${page.installDir()}">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <fieldset class="layui-elem-field layui-field-title">
            <legend>${page.lang().get("install/other_config")}</legend>
        </fieldset>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/admin_name")}</label>
            <div class="layui-input-inline w200">
                <input type="text" class="layui-input" name="account" lay-verify="title">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/admin_name_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/admin_pass")}</label>
            <div class="layui-input-inline w200">
                <input type="password" class="layui-input" name="password" lay-verify="title">
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/admin_pass_tip")}</div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/init_data")}</label>
            <div class="layui-input-inline w200" >
                <input type="radio" name="initdata" value="1" title="${page.lang().get("install/create")}" checked="">
                <input type="radio" name="initdata" value="0" title="${page.lang().get("install/not_create")}" >
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/create_tip")}</div>
        </div>
        <div class="step-btns">
            <a href="?step=2" class="layui-btn layui-btn-primary layui-btn-big fl">${page.lang().get("install/back_step")}</a>
            <button type="submit" class="layui-btn layui-btn-big layui-btn-normal fr" lay-submit="" lay-filter="formSubmit" >${page.lang().get("install/exec")}</button>
        </div>
    </form>
</div>
<span style="display: none">
</span>
`, scripts = @`
<script type="text/javascript" src="${page.assetsPath()}/step3.js" data-wait="${page.lang().get("wait_submit")}" data-submit-tip="${page.lang().get("install/submit_tip")}"></script>
`)
