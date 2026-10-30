@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="layui-tab layui-tab-brief" lay-filter="tabs">
        <ul class="layui-tab-title">
            <li class="layui-this btn-local" data-href="${page.url("downloaded")}">${page.lang().get("local_app")}</li>
        </ul>
        <div class="layui-tab-content">
            <div class="layui-tab-item layui-show">

    <div class="my-toolbar-box" >
        <div class="center mb10">
        <form class="layui-form " method="post">
            <div class="layui-input-inline w300">
                <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.v(page.filterXss(page.at("param.wd")))}">
            </div>
            <button class="layui-btn mgl-20 j-search" >${page.lang().get("btn_search")}</button>
        </form>
        </div>
    </div>

    <form class="layui-form p10 " method="post" id="pageListForm">
        <div class="layui-row layui-col-space15" id="addon_list">

        </div>
        <div id="pages" class="center"></div>
    </form>
</div>
            </div>
        </div>
    </div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/addon-index.js" data-state-url="${page.url("addon/state")}" data-uninstall-url="${page.url("addon/uninstall")}" data-config-url="${page.url("config")}" data-lang-wait="${page.lang().get("wait_submit")}" data-lang-level="${page.lang().get("level")}" data-lang-author="${page.lang().get("author")}" data-lang-intro="${page.lang().get("intro")}" data-lang-ver="${page.lang().get("ver")}" data-lang-update-time="${page.lang().get("update_time")}" data-lang-config="${page.lang().get("config")}" data-lang-disable="${page.lang().get("disable")}" data-lang-enable="${page.lang().get("enable")}" data-lang-uninstall="${page.lang().get("uninstall")}" data-lang-install="${page.lang().get("install")}" data-lang-detail="${page.lang().get("detail")}"></script>
`)
