@import com.maccms.backend.admin.web.view.collect.CjShowUrlView
@import com.maccms.backend.collect.application.CjRunService.UrlEntry
@param CjShowUrlView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <fieldset class="layui-elem-field">
        <legend>${page.lang().get("base_info")}</legend>
        <div class="layui-field-box">
            @if(!page.htmlConfigured())
            <p class="red">未配置本地 HTML 目录（maccms.collect.local.html-dir），采集时读不到页面</p>
            <hr>
            @endif
            @for(UrlEntry vo : page.urls())
            <p>${vo.url()}</p>
            @if(vo.localPath() == null)
            <p class="red">（网址不能映射到本地文件）</p>
            @elseif(vo.exists())
            <p>本地文件：${vo.localPath()}</p>
            @else
            <p class="red">本地文件：${vo.localPath()}（不存在）</p>
            @endif
            <hr>
            @endfor
        </div>
    </fieldset>
</div>
`)
