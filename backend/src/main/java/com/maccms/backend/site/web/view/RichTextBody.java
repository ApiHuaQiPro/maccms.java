package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeHtml;
import gg.jte.Content;
import gg.jte.TemplateOutput;

/**
 * 详情正文：把 {@link SafeHtml}（只有 RichTextSanitizer 能创建）适配为 jte 的 {@link Content}，
 * 只能放在元素内容位置输出（07 文档第 5 节）。其他字符串无法构造出本类型的非空实例。
 */
public record RichTextBody(SafeHtml html) implements Content {

    public static final RichTextBody EMPTY = new RichTextBody(SafeHtml.EMPTY);

    public RichTextBody {
        if (html == null) {
            throw new IllegalArgumentException("html 不能为空");
        }
    }

    @Override
    public void writeTo(TemplateOutput output) {
        output.writeContent(html.html());
    }

    @Override
    public boolean isEmptyContent() {
        return html.isEmpty();
    }
}
