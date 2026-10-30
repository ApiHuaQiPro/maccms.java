@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <form class="layui-form layui-form-pane" action="">
        <div class="layui-tab" lay-filter="tb1">
            <ul class="layui-tab-title">
                @for(var item_vo : page.each(page.at("extends.ext_list")))
                <li data-key="${page.v(item_vo.key())}" lay-id="configpay_${item_vo.i() + 2}">${page.t(item_vo.value())}${page.lang().get("config")}</li>
                @endfor

            </ul>
            <div class="layui-tab-content">

                @template.admin.pages.system.extend.html(page = page, flag = "urlsend")

            </div>
        </div>
        <div class="layui-form-item ">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>


    <form class="layui-form layui-form-pane" method="post" action="" target="_blank" id="form_post">
        <blockquote class="layui-elem-quote">
            ${page.lang().get("admin/urlsend/tip2")}${page.t(page.at("site_root"))}<br>
        </blockquote>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/urlsend/send_genre")}：</label>
            <div class="layui-input-inline">
                <select class="w150" id="ac" name="ac" lay-filter="ac">
                    @for(var item_vo : page.each(page.at("extends.ext_list")))
                    <option value="${page.v(item_vo.key())}">${page.t(item_vo.value())}</option>
                    @endfor
                </select>
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/urlsend/send_range")}：</label>
            <div class="layui-input-inline">
                <div class="layui-input-inline w300">
                    <input type="radio" name="range" value="0" title="${page.lang().get("admin/urlsend/add_update")}" checked >
                    <input type="radio" name="range" value="1" title="${page.lang().get("admin/urlsend/add")}">
                </div>
            </div>
        </div>

        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/urlsend/page_send_num")}：</label>
            <div class="layui-input-inline w400">
                <input type="text" name="limit" id="limit" value="50" class="layui-input">
            </div>
            <div class="layui-form-mid layui-word-aux"></div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">${page.lang().get("admin/urlsend/start_page")}：</label>
            <div class="layui-input-inline w400">
                <input type="text" name="page" id="page" value="1" class="layui-input">
            </div>
            <div class="layui-form-mid layui-word-aux"></div>
        </div>

        <hr class="layui-bg-gray">
        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("vod")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=1">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("vod")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=1">

        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("art")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=2">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("art")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=2">
        <hr class="layui-bg-gray">
        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("topic")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=3">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("topic")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=3">

        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("actor")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=8">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("actor")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=8">
        <hr class="layui-bg-gray">
        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("role")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=9">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("role")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=9">

        <input type="button" value="${page.lang().get("that_day")}${page.lang().get("website")}" class="layui-btn layui-btn-primary" data-post="ac2=today&mid=11">
        <input type="button" value="${page.lang().get("all")}${page.lang().get("website")}" class="layui-btn layui-btn-primary" data-post="ac2=all&mid=11">
        <hr class="layui-bg-gray">
        @if(!page.eq(page.at("urlsend_break_baidu_push"), ""))
        <a href="${page.v(page.at("urlsend_break_baidu_push"))}" class="layui-btn layui-btn-danger ">【${page.lang().get("admin/urlsend/in_break_point_exec")}】</a>
        @endif

    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.cookie.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/urlsend-index.js" data-push-url="${page.url("push")}"></script>
`)
