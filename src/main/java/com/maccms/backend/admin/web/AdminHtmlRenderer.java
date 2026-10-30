package com.maccms.backend.admin.web;

import com.maccms.backend.admin.web.jte.Templates;
import gg.jte.models.runtime.JteModel;
import gg.jte.output.StringOutput;
import java.util.function.Function;

/**
 * 后台页面的缓冲渲染（与 site 相同的做法，02 规范第 4 节）：只通过 jte-models 生成的 {@link Templates} 接口调用，先完整渲染到字符串，
 * 成功后才构造响应；渲染失败抛 {@link AdminRenderException}，由 {@link AdminExceptionHandler} 输出完整的 500 页。
 */
public class AdminHtmlRenderer {

    private final Templates templates;

    public AdminHtmlRenderer(Templates templates) {
        this.templates = templates;
    }

    public String render(Function<Templates, JteModel> page) {
        try {
            StringOutput out = new StringOutput();
            page.apply(templates).render(out);
            return out.toString();
        } catch (RuntimeException e) {
            throw new AdminRenderException(e);
        }
    }

    /** 模板或视图模型在渲染中抛出的异常。 */
    public static final class AdminRenderException extends RuntimeException {
        AdminRenderException(Throwable cause) {
            super(cause);
        }
    }
}
