package com.maccms.spike.jte;

import gg.jte.generated.precompiled.StaticTemplates;
import gg.jte.generated.precompiled.Templates;
import gg.jte.models.runtime.JteModel;
import gg.jte.output.StringOutput;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 缓冲渲染 + 类型安全入口：通过 jte-models 生成的 {@link Templates} 接口调用模板，
 * Controller 传给模板的参数类型与个数在编译期检查（不经过 Map&lt;String,Object&gt;）。
 * 先渲染到 StringOutput，成功后才写响应；失败时由 @ExceptionHandler 输出完整 500 页。
 */
@Component
public class BufferedHtmlRenderer {

    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final Templates templates = new StaticTemplates();

    public String renderToString(Function<Templates, JteModel> page) {
        try {
            StringOutput out = new StringOutput();
            page.apply(templates).render(out);
            return out.toString();
        } catch (RuntimeException e) {
            throw new TemplateRenderException(e);
        }
    }

    public ResponseEntity<String> ok(Function<Templates, JteModel> page) {
        return ResponseEntity.ok().contentType(HTML_UTF8).body(renderToString(page));
    }

    /** 错误页本身不带模型，渲染不依赖业务数据。 */
    public ResponseEntity<String> error(HttpStatus status) {
        return ResponseEntity.status(status).contentType(HTML_UTF8).cacheControl(CacheControl.noStore())
                .body(templates.error().render());
    }

    public static final class TemplateRenderException extends RuntimeException {
        TemplateRenderException(Throwable cause) {
            super("template render failed", cause);
        }
    }
}
