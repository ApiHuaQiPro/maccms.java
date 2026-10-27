@import com.maccms.backend.admin.web.view.collect.CollectApiView
@import com.maccms.backend.collect.application.CollectPreviewService.TypeBinding
@import com.maccms.backend.collect.application.CollectPreviewService.PreviewRow
@import com.maccms.backend.collect.application.CollectPreviewService.LocalFile
@param CollectApiView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">

    <div class="my-toolbar-box">

        <div class="mb10">
            <form class="layui-form" method="post" enctype="multipart/form-data" action="${page.actionUrl("localload")}">
                <div class="layui-input-inline w150 m5">本地文件：</div>
                @if(page.preview() != null)
                <div class="layui-input-inline m5"><span class="red">${page.preview().sourceName()}</span>（${page.loadedAt()} 载入）</div>
                @else
                <div class="layui-input-inline m5"><span class="red">尚未载入</span></div>
                @endif
                <div class="layui-input-inline m5"><input type="file" name="file" accept=".json,.xml,.txt"></div>
                @if(page.localConfigured())
                <div class="layui-input-inline w150 m5">
                    <select name="local" lay-ignore>
                        <option value="">从本地目录选择</option>
                        @for(LocalFile f : page.localFiles())
                        <option value="${f.name()}">${f.name()}（${page.size(f.size())}）</option>
                        @endfor
                    </select>
                </div>
                @endif
                <button type="submit" class="layui-btn layui-btn-sm m5">载入</button>
            </form>
            <form class="layui-form layui-input-inline" method="post" action="${page.actionUrl("localclear")}">
                <button type="submit" class="layui-btn layui-btn-primary layui-btn-sm m5">清除预览缓存</button>
            </form>
            <div class="m5">上传上限 ${page.maxUpload()}，文件上限 ${page.size(page.maxBytes())}；更大的文件请放进本地目录。新系统不连接资源站，只解析本地文件。</div>
        </div>

        @if(page.preview() != null)
        @if(page.preview().kind().binds())
        <div class="mb10">
            <div class="layui-input-inline w150 m5"><a href="javascript:;" data-id="" class="select_type red">${page.lang().get("admin/collect/view_all_resource")}</a></div>
            @for(TypeBinding vo : page.preview().types())
            <div class="layui-input-inline w150 m5">
                <a href="javascript:;" data-id="${vo.typeId()}" class="select_type">${vo.typeName()}</a>
                <a id="${page.bindId(vo.typeId())}" data-href="${page.selectHref(vo.typeId())}" data-width="270" data-height="100" class="j-select" >
                    @if(vo.bound())
                    <span class="red">[${vo.localTypeName()}]</span>
                    @else
                    [${page.lang().get("bind")}]
                    @endif
                </a>
            </div>
            @endfor

        </div>
        @endif

        <div class="center mb10">
            <form class="layui-form " method="">
                <div class="layui-input-inline">
                    @if(page.kind() == com.maccms.backend.collect.application.ProvideKind.VOD)
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" id="wd" name="wd" value="${page.p("wd")}">
                    @else
                    <input type="text" autocomplete="off" placeholder="${page.lang().get("wd")}" class="layui-input" name="wd" value="${page.p("wd")}">
                    @endif
                </div>
                <button type="button" class="layui-btn mgl-20 j-btn" >${page.lang().get("btn_search")}</button>
            </form>
        </div>
        @endif

    </div>

    @if(page.preview() != null)
    <form class="layui-form " method="post" id="pageListForm">
        <table class="layui-table" lay-size="sm">
            <thead>
            <tr>
                <th width="25"><input type="checkbox" lay-skin="primary" lay-filter="allChoose"></th>
                <th >${page.lang().get("name")}</th>
                <th width="${page.head3Width()}">${page.lang().get(page.head3())}</th>
                <th width="${page.head4Width()}">${page.lang().get(page.head4())}</th>
                <th width="140">${page.lang().get("time")}</th>
            </tr>
            </thead>

            @for(PreviewRow vo : page.preview().rows())
            <tr>
                <td><input type="checkbox" name="ids[]" value="${vo.s(page.idField())}" class="layui-checkbox checkbox-ids" lay-skin="primary"></td>
                <td>${vo.s(page.kind().code() + "_name")}@if(!vo.check().isEmpty()) <span class="red">[${page.lang().get(vo.check())}]</span>@endif</td>
                <td>${vo.s(page.col3())}</td>
                <td>${vo.s(page.col4())}</td>
                <td>@if(page.today(vo.s(page.timeField())))<font color="#FF0000">${page.day(vo.s(page.timeField()))}</font>@else${page.day(vo.s(page.timeField()))}@endif</td>
            </tr>
            @endfor
        </table>
        <div class="layui-btn-group">
            <a data-href="${page.collectUrl("&ac=cjsel")}" data-ajax="no" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe654;</i>${page.lang().get("admin/collect/cj_select")}</a>
            <a data-href="${page.collectUrl("&h=24&ac=cjday")}" data-checkbox="no" data-ajax="no" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe654;</i>${page.lang().get("admin/collect/cj_today")}</a>
            <a data-href="${page.collectUrl("&ac=cjall")}" data-checkbox="no" data-ajax="no" class="layui-btn layui-btn-primary j-page-btns"><i class="layui-icon">&#xe654;</i>${page.lang().get("admin/collect/cj_all")}</a>
        </div>

        <div id="pages" class="center"></div>
    </form>
    @endif

</div>
`, scripts = @`
@if(page.preview() != null)
<script type="text/javascript" src="${page.head().assetsPath()}/collect-api.js" data-cur-url="${page.curUrl()}" data-count="${page.preview().total()}" data-limit="${page.preview().limit()}" data-curr="${page.preview().page()}" data-bind-style="${page.bindStyle()}" data-msg-bind="${page.lang().get("bind")}" data-msg-unbind="${page.lang().get("unbind")}"></script>
@endif
`)
