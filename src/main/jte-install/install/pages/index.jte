@import com.maccms.backend.install.web.view.IndexPage
@import com.maccms.backend.install.web.view.InstallText
@param IndexPage page
@template.install.layout.page(lang = page.lang(), staticPath = page.staticPath(), content = @`
<div class="install-box">
    <form class="layui-form layui-form-pane" action="" method="post">
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("install/lang")}</label>
            <div class="layui-input-inline w200 ">
                <select class="" name="lang" lay-filter="lang" style="z-index:99999;">
                    <option value="">${page.lang().get("install/select_lang")}</option>
                    @for(String vo : page.langs())
                    <option value="${vo}" selected="${vo.equals(page.selected())}">${vo}</option>
                    @endfor
                </select>
            </div>
            <div class="layui-form-mid layui-word-aux">${page.lang().get("install/lang_tip")}</div>
        </div>
    </form>

    <fieldset class="layui-elem-field site-demo-button">
        <legend>${page.lang().get("install/user_agreement_title")}</legend>
        <div class="protocol">
            <p>
                ${InstallText.trusted(page.lang().get("install/user_agreement"))}
            </p>
        </div>
    </fieldset>
    <div class="step-btns">
        <a href="?step=2" class="layui-btn layui-btn-big layui-btn-normal">${page.lang().get("install/user_agreement_agree")}</a>
    </div>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.assetsPath()}/index.js" data-url="${page.indexUrl()}"></script>
`)
