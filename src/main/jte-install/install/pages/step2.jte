@import com.maccms.backend.install.application.InstallEnvironment.Row
@import com.maccms.backend.install.web.view.Step2Page
@param Step2Page page
@template.install.layout.page(lang = page.lang(), staticPath = page.staticPath(), styles = @`
<style type="text/css">
.layui-table td, .layui-table th{text-align:left;}
.layui-table tbody tr.no{background-color:#f00;color:#fff;}
</style>
`, content = @`
<div class="install-box">
    <fieldset class="layui-elem-field layui-field-title">
        <legend>${page.lang().get("install/environment_title")}</legend>
    </fieldset>
    <table class="layui-table" lay-skin="line">
        <thead>
            <tr>
                <th>${page.lang().get("install/environment_name")}</th>
                <th>${page.lang().get("install/required_config")}</th>
                <th>${page.lang().get("install/current_config")}</th>
            </tr>
        </thead>
        <tbody>
            @for(Row vo : page.env().env())
            <tr class="${vo.state()}">
                <td>${vo.name()}</td>
                <td>${vo.required()}</td>
                <td>${vo.current()}</td>
            </tr>
            @endfor
        </tbody>
    </table>
    <table class="layui-table" lay-skin="line">
        <thead>
            <tr>
                <th>${page.lang().get("install/dir_file")}</th>
                <th>${page.lang().get("install/required_popedom")}</th>
                <th>${page.lang().get("install/current_popedom")}</th>
            </tr>
        </thead>
        <tbody>
            @for(Row vo : page.env().dir())
            <tr class="${vo.state()}">
                <td>${vo.name()}</td>
                <td>${vo.required()}</td>
                <td>${vo.current()}</td>
            </tr>
            @endfor
        </tbody>
    </table>
    <table class="layui-table" lay-skin="line">
        <thead>
            <tr>
                <th>${page.lang().get("install/func_ext")}</th>
                <th>${page.lang().get("install/type")}</th>
                <th>${page.lang().get("install/result")}</th>
            </tr>
        </thead>
        <tbody>
            @for(Row vo : page.env().func())
            <tr class="${vo.state()}">
                <td>${vo.name()}</td>
                <td>${vo.required()}</td>
                <td>${vo.current()}</td>
            </tr>
            @endfor
        </tbody>
    </table>
    <div class="step-btns">
        <a href="?index" class="layui-btn layui-btn-primary layui-btn-big fl">${page.lang().get("install/back_step")}</a>

        <a href="?step=3" class="layui-btn layui-btn-big layui-btn-normal fr">${page.lang().get("install/next_step")}</a>
    </div>
</div>
`)
