@import com.maccms.backend.admin.web.view.collect.CjProgramView
@import java.util.Map
@param CjProgramView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="id" value="${page.id()}">
        <fieldset class="layui-elem-field">
            <legend>${page.lang().get("admin/cj/label_data_rel")}</legend>
        </fieldset>

                    <table class="layui-table" lay-size="sm" style="width:600px;">
                        <thead>
                        <tr>
                            <th width="100">${page.lang().get("admin/cj/data_column")}</th>
                            <th width="100">${page.lang().get("admin/cj/label_column")}</th>
                            <th width="100">${page.lang().get("admin/cj/processing_function")}</th>
                        </tr>
                        </thead>
                        <tbody>
                        @for(String col : page.program().columns())
                        <tr>
                            <td><input type="hidden" name="model_field[]" value="${col}">${col}</td>
                            <td><select name="node_field[]">
                                <option value="">${page.lang().get("select_please")}</option>
                                @for(Map.Entry<String, String> vo2 : page.program().nodeFields().entrySet())
                                <option value="${page.attr(vo2.getKey())}" selected="${page.mapped(col, vo2.getKey())}">${page.text(vo2.getValue())}</option>
                                @endfor
                            </select>
                            </td>
                            <td><select name="funcs[]"><option value="" >${page.lang().get("select_please")}</option><option value="trim" selected="${page.trimmed(col)}">${page.lang().get("admin/cj/trim_space")}</option></select></td>
                        </tr>
                        @endfor
                        </tbody>
                    </table>

        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit" data-child="true">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>

`)
