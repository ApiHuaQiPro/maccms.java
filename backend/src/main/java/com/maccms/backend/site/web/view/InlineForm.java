package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 一个只有隐藏字段与按钮的普通表单（B11，{@code site/interaction/inlineForm.jte}）：评论的顶、踩、举报，内容页的顶、踩。页面不带脚本，
 * 提交后由服务端返回提示页（非 ajax 请求的承接形态，20 文档 D-B11-10）。
 */
public record InlineForm(SafeUrl action, List<HiddenField> fields, String label) {

    public InlineForm {
        fields = List.copyOf(fields);
    }
}
