package com.maccms.spike.thymeleaf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * 缓冲渲染：先把整页渲染成字符串，成功后才写响应。渲染异常发生在 Controller 内，
 * 由 @ExceptionHandler 输出完整的 500 页，不会出现"200 + 半页 HTML"。
 */
@Component
public class BufferedHtmlRenderer {

    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final ITemplateEngine engine;

    public BufferedHtmlRenderer(ITemplateEngine engine) {
        this.engine = engine;
    }

    public String renderToString(String template, Map<String, Object> variables) {
        try {
            return engine.process(template, new Context(Locale.SIMPLIFIED_CHINESE, variables));
        } catch (RuntimeException e) {
            throw new TemplateRenderException(e);
        }
    }

    public ResponseEntity<String> ok(String template, Map<String, Object> variables) {
        return ResponseEntity.ok().contentType(HTML_UTF8).body(renderToString(template, variables));
    }

    /** 错误页本身不带模型，渲染不依赖业务数据。 */
    public ResponseEntity<String> error(HttpStatus status) {
        return ResponseEntity.status(status).contentType(HTML_UTF8).cacheControl(CacheControl.noStore())
                .body(engine.process("error", new Context(Locale.SIMPLIFIED_CHINESE)));
    }

    public static final class TemplateRenderException extends RuntimeException {
        TemplateRenderException(Throwable cause) {
            super("template render failed", cause);
        }
    }
}
