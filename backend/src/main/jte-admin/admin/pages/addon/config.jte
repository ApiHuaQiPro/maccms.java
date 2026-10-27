@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
<%-- 旧 application/admin/view/addon/config.html：插件配置项按 type 输出（ThinkPHP 的 switch/case；select 与 selects、image 与 images、
     file 与 files 走同一段）。旧模板把 {$item.extend} 原样拼进标签（任意额外属性），新版不输出（24 文档 D-B15-12）。 --%>
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <div class="showpic" style="display:none;"><img class="showpic_img" width="120" height="160"></div>

    <form class="layui-form layui-form-pane" method="post" action="">
        <input id="admin_id" name="name" type="hidden" value="${page.v(page.at("info.name"))}">

        @for(var item : page.each(page.at("config")))
        <div class="layui-form-item">
            <label class="layui-form-label">${page.t(page.at(item.value(), "title"))}：</label>
                @if(page.eq(page.at(item.value(), "type"), "string"))
                <div class="layui-input-inline w600 ">
                <input type="text" name="row[${page.s(page.at(item.value(), "name"))}]" value="${page.v(page.at(item.value(), "value"))}" class="layui-input" data-rule="${page.v(page.at(item.value(), "rule"))}" data-tip="${page.v(page.at(item.value(), "tip"))}" />
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "text"))
                <div class="layui-input-inline w600 ">
                <textarea name="row[${page.s(page.at(item.value(), "name"))}]" class="layui-input" data-rule="${page.v(page.at(item.value(), "rule"))}" rows="5" data-tip="${page.v(page.at(item.value(), "tip"))}">${page.t(page.at(item.value(), "value"))}</textarea>
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "array"))
                  <!-- 数组类型标签开始 -->
                  <div class="layui-input-inline w700 ">
                     <div style="padding-left: 10px;">
                        @for(var vo : page.each(page.at(item.value(), "value")))
                           <div class=" w700 ">
                              <div class="layui-form-item">
                                 <label class="layui-form-label">${page.t(vo.key())}:</label>
                                 <div class="layui-input-block">
                                    <input type="text" name="row[${page.s(page.at(item.value(), "name"))}][${page.s(vo.key())}]" value="${page.v(vo.value())}" class="layui-input w500"  />
                                 </div>
                              </div>
                           </div>
                        @endfor
                     </div>
                  </div>
                  <!-- 数组类型标签结束 -->

                @elseif(page.eq(page.at(item.value(), "type"), "datetime"))
            <div class="layui-input-inline w600 ">
                <input type="text" name="row[${page.s(page.at(item.value(), "name"))}]" value="${page.v(page.at(item.value(), "value"))}" class="layui-input datetimepicker" data-tip="${page.v(page.at(item.value(), "tip"))}" data-rule="${page.v(page.at(item.value(), "rule"))}" />
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "number"))
            <div class="layui-input-inline w600 ">
                <input type="number" name="row[${page.s(page.at(item.value(), "name"))}]" value="${page.v(page.at(item.value(), "value"))}" class="layui-input" data-tip="${page.v(page.at(item.value(), "tip"))}" data-rule="${page.v(page.at(item.value(), "rule"))}" />
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "checkbox"))
            <div class="layui-input-inline w600 ">
                @for(var vo : page.each(page.at(item.value(), "content")))
                <input id="row[${page.s(page.at(item.value(), "name"))}][]-${page.s(vo.key())}" name="row[${page.s(page.at(item.value(), "name"))}][]" type="checkbox" value="${page.s(vo.key())}" data-tip="${page.v(page.at(item.value(), "tip"))}" title="${page.v(vo.value())}" checked="${page.in(vo.key(), page.at(item.value(), "value"))}" />
                @endfor
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "radio"))
            <div class="layui-input-inline w700 ">
                @for(var vo : page.each(page.at(item.value(), "content")))
                <input id="row[${page.s(page.at(item.value(), "name"))}]-${page.s(vo.key())}" name="row[${page.s(page.at(item.value(), "name"))}]" type="radio" value="${page.s(vo.key())}" data-tip="${page.v(page.at(item.value(), "tip"))}" title="${page.v(vo.value())}" checked="${page.in(vo.key(), page.at(item.value(), "value"))}" />
                @endfor
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "select") || page.eq(page.at(item.value(), "type"), "selects"))
            <div class="layui-input-inline w700 ">
                <select name="row[${page.s(page.at(item.value(), "name"))}]${page.eq(page.at(item.value(), "type"), "selects") ? "[]" : ""}" class="layui-input selectpicker" data-tip="${page.v(page.at(item.value(), "tip"))}" multiple="${page.eq(page.at(item.value(), "type"), "selects")}">
                    @for(var vo : page.each(page.at(item.value(), "content")))
                    <option value="${page.s(vo.key())}" selected="${page.in(vo.key(), page.at(item.value(), "value"))}">${page.t(vo.value())}</option>
                    @endfor
                </select>
                </div>
                @elseif(page.eq(page.at(item.value(), "type"), "image") || page.eq(page.at(item.value(), "type"), "images"))
            <div class="layui-input-inline w600 ">
                <input id="c-${page.s(page.at(item.value(), "name"))}" class="layui-input upload-input upload-img" size="37" name="row[${page.s(page.at(item.value(), "name"))}]" type="text" value="${page.v(page.at(item.value(), "value"))}" data-tip="${page.v(page.at(item.value(), "tip"))}">
                </div>
            <div class="layui-input-inline w100">
                <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:''}}" >上传</button>
                </div>

                @elseif(page.eq(page.at(item.value(), "type"), "file") || page.eq(page.at(item.value(), "type"), "files"))
            <div class="layui-input-inline w600 ">
                <input id="c-${page.s(page.at(item.value(), "name"))}" class="layui-input upload-input" size="37" name="row[${page.s(page.at(item.value(), "name"))}]" type="text" value="${page.v(page.at(item.value(), "value"))}" data-tip="${page.v(page.at(item.value(), "tip"))}">
            </div>
            <div class="layui-input-inline w100">
                <button type="button" class="layui-btn layui-upload" lay-data="{data:{thumb:0,thumb_class:''}}" >上传</button>
            </div>

                @elseif(page.eq(page.at(item.value(), "type"), "bool"))
                <div class="layui-input-inline">
                <input id="row[${page.s(page.at(item.value(), "name"))}]-yes" name="row[${page.s(page.at(item.value(), "name"))}]" type="radio" value="1" checked="${page.truthy(page.at(item.value(), "value"))}" data-tip="${page.v(page.at(item.value(), "tip"))}" title="是"/>
                <input id="row[${page.s(page.at(item.value(), "name"))}]-no" name="row[${page.s(page.at(item.value(), "name"))}]" type="radio" value="0" checked="${!page.truthy(page.at(item.value(), "value"))}" data-tip="${page.v(page.at(item.value(), "tip"))}" title="否"/>
                </div>
                @endif
            <div class="layui-form-mid layui-word-aux">${page.t(page.at(item.value(), "tip"))}</div>
            </div>


        @endfor

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/addon-config.js" data-upload-url="${page.url("upload/upload")}" data-msg-uploading="${page.lang().get("upload_ing")}"></script>
`)
