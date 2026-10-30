package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.content.application.view.AdminRow;

/**
 * 表单页的 {@code {$info.x}}：表单行的列原样输出时浏览器看到的值（见 {@link BrowserText}）。
 */
public interface RawOutput extends BrowserText {

    AdminRow info();

    /** 表单行的列放在属性值里（{@code value="{$info.x}"}）时浏览器看到的值。 */
    default String v(String col) {
        return attr(info().s(col));
    }

    /** 表单行的列放在文本里（如 {@code <textarea>{$info.x}</textarea>}）时浏览器看到的文字。 */
    default String t(String col) {
        return text(info().s(col));
    }
}
