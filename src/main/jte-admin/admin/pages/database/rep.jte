@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/database/rep.html（批量替换）。旧模板写在正文里的样式块改放进页头的 styles（内容不变）。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<style>
    .layui-form-select ul {max-height:200px}
    .layui-btn+.layui-btn{margin-left:0px; }
</style>
`, content = @`
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/database/batch_replace")}</li>
            </ul>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/database/select_table")}：</label>
                    <div class="layui-input-inline w400" >
                        <select name="table" lay-filter="table" lay-verify="table">
                            @for(var vo : page.each(page.at("list")))
                                <option value="${page.v(page.at(vo.value(), "Name"))}">${page.t(page.at(vo.value(), "Name"))}【${page.t(page.at(vo.value(), "Comment"))}】</option>
                            @endfor
                        </select>
                    </div>
                </div>
                <div class="layui-form-item row-fields">
                    <label class="layui-form-label">${page.lang().get("admin/database/select_col")}：</label>
                    <div class="layui-input-block fields" >
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/database/field")}：</label>
                    <div class="layui-input-block" >
                        <input type="text" id="field" name="field" placeholder="" lay-verify="field" class="layui-input">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/database/findstr")}：</label>
                        <div class="layui-input-block" >
                            <textarea name="findstr" placeholder="" lay-verify="findstr" class="layui-textarea"></textarea>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/database/tostr")}：</label>
                        <div class="layui-input-block" >
                            <textarea name="tostr" placeholder="" lay-verify="tostr" class="layui-textarea"></textarea>
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/database/where")}：</label>
                        <div class="layui-input-block" >
                            <input type="text" name="where" placeholder="" value="" class="layui-input">
                        </div>
                    </div>
                <div class="layui-form-item">
                </div>
            </div>
            </div>
        </div>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/database-rep.js" data-columns-url="${page.url("columns")}" data-msg-table="${page.lang().get("admin/database/select_table")}" data-msg-field="${page.lang().get("admin/database/select_col")}" data-msg-findstr="${page.lang().get("admin/database/findstr")}" data-msg-tostr="${page.lang().get("admin/database/tostr")}"></script>
`)
