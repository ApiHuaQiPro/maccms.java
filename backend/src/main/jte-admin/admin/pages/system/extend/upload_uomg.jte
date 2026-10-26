@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<div class="layui-form-item upload_mode mode_Uomg" style="${!page.eq(page.at("config.upload.mode"), "Uomg") ? "display:none;" : null}">
<label class="layui-form-label">优启梦图床：</label>
<div class="layui-input-block">
    <a href="http://api.uomg.com/" target="_blank" class="layui-btn layui-btn-primary">目前免费不用申请 http://api.uomg.com/</a>
</div>
</div>
<div class="layui-form-item upload_mode mode_Uomg" style="${!page.eq(page.at("config.upload.mode"), "Uomg") ? "display:none;" : null}">
<label class="layui-form-label">OPENID：</label>
<div class="layui-input-inline w200">
    <input type="text" name="upload[api][uomg][openid]" placeholder="" value="${page.v(page.at("config.upload.api.uomg.openid"))}" class="layui-input"  >
</div>
<label class="layui-form-label">操作员名：</label>
<div class="layui-input-inline w200">
    <input type="text" name="upload[api][uomg][key]" placeholder="" value="${page.v(page.at("config.upload.api.uomg.key"))}" class="layui-input"  >
</div>
<label class="layui-form-label">类型：</label>
<div class="layui-input-inline w200">
    <select class="w150" name="upload[api][uomg][type]">
        <option value="ali" selected="${page.eq(page.at("config.upload.api.uomg.type"), "ali")}">阿里巴巴</option>
        <option value="baidu" selected="${page.eq(page.at("config.upload.api.uomg.type"), "baidu")}">百度识图</option>
        <option value="sina" selected="${page.eq(page.at("config.upload.api.uomg.type"), "sina")}">新浪微博</option>
        <option value="sogou" selected="${page.eq(page.at("config.upload.api.uomg.type"), "sogou")}">搜狗图片</option>
        <option value="juejin" selected="${page.eq(page.at("config.upload.api.uomg.type"), "juejin")}">掘金社区</option>
        <option value="360" selected="${page.eq(page.at("config.upload.api.uomg.type"), "360")}">奇虎360</option>
        <option value="jd" selected="${page.eq(page.at("config.upload.api.uomg.type"), "jd")}">京东商城</option>
    </select>
</div>
</div>
