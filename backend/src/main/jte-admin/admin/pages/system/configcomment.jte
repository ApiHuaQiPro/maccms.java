@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">

    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160" referrerPolicy="no-referrer"></div>

    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab">
            <ul class="layui-tab-title">
                <li class="layui-this">${page.lang().get("admin/system/configcomment/title")}</li>
            </ul>
            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/gbook")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="gbook[status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.gbook.status"), 1L)}">
                        <input type="radio" name="gbook[status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.gbook.status"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcomment/gbook_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/audit")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="gbook[audit]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.gbook.audit"), 1L)}">
                        <input type="radio" name="gbook[audit]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.gbook.audit"), 1L)}">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcomment/login")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="gbook[login]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.gbook.login"), 1L)}">
                            <input type="radio" name="gbook[login]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.gbook.login"), 1L)}">
                        </div>
                    </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/verify")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="gbook[verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.gbook.verify"), 1L)}">
                        <input type="radio" name="gbook[verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.gbook.verify"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/pagesize")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="gbook[pagesize]" placeholder="${page.lang().get("admin/system/configcomment/pagesize_tip")}" value="${page.v(page.at("config.gbook.pagesize"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/timespan")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="gbook[timespan]" placeholder="${page.lang().get("admin/system/configcomment/timespan_tip")}" value="${page.v(page.at("config.gbook.timespan"))}" class="layui-input w150">
                    </div>
                </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/comment")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="comment[status]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.comment.status"), 1L)}">
                        <input type="radio" name="comment[status]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.comment.status"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcomment/comment_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/audit")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="comment[audit]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.comment.audit"), 1L)}">
                        <input type="radio" name="comment[audit]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.comment.audit"), 1L)}">
                    </div>
                </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcomment/login")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="comment[login]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.comment.login"), 1L)}">
                            <input type="radio" name="comment[login]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.comment.login"), 1L)}">
                        </div>
                    </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/verify")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="comment[verify]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.comment.verify"), 1L)}">
                        <input type="radio" name="comment[verify]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.comment.verify"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/pagesize")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="comment[pagesize]" placeholder="${page.lang().get("admin/system/configcomment/pagesize_tip")}" value="${page.v(page.at("config.comment.pagesize"))}" class="layui-input w150">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcomment/timespan")}：</label>
                    <div class="layui-input-block">
                        <input type="text" name="comment[timespan]" placeholder="${page.lang().get("admin/system/configcomment/timespan_tip")}" value="${page.v(page.at("config.comment.timespan"))}" class="layui-input w150">
                    </div>
                </div>

            </div>

                <div class="layui-form-item center">
                    <div class="layui-input-block">
                        <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                        <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
                    </div>
                </div>
            </div>
        </div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/layui-form.js"></script>
`)
