@import com.maccms.backend.admin.web.view.collect.CjShowView
@import java.util.Map
@param CjShowView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`

<div class="page-container p10">
    <form class="layui-form layui-form-pane" method="post" action="">

        <div class="layui-form-item">
            <label class="layui-form-label">url：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.attr(page.shown().info().s("url"))}" lay-verify="url" placeholder="" id="url" name="url">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">title：</label>
            <div class="layui-input-block">
                <input type="text" class="layui-input" value="${page.attr(page.shown().info().s("title"))}" lay-verify="title" placeholder="" id="title" name="title">
            </div>
        </div>
        <div class="layui-form-item">
            <label class="layui-form-label">other：</label>
            <div class="layui-input-block">
                @for(Map.Entry<String, String> vo : page.shown().data().entrySet())
                    ${page.text(vo.getKey())}<input type="text" class="layui-input" value="${page.attr(vo.getValue())}"><br>
                @endfor
            </div>
        </div>

        <div class="layui-form-item center">
            <div class="layui-input-block">

            </div>
        </div>
    </form>

</div>
`)
