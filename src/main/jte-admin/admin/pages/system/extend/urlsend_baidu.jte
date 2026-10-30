@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<div class="layui-tab-item ">

    <div class="layui-form-item">
        <label class="layui-form-label">token：</label>
        <div class="layui-input-inline  ">
            <input type="text" class="layui-input" value="${page.v(page.at("config.baidu.token"))}" placeholder="" name="urlsend[baidu][token]">
        </div>
    </div>

</div>
