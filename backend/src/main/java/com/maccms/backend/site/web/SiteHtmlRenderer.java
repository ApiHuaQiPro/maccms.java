package com.maccms.backend.site.web;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.site.web.jte.Templates;
import com.maccms.backend.site.web.view.ErrorPage;
import gg.jte.models.runtime.JteModel;
import gg.jte.output.StringOutput;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 缓冲渲染（02 规范第 4 节、04 报告第 3.5 节）：页面只通过 jte-models 生成的 {@link Templates} 接口调用
 * （参数类型与个数在编译期检查），先完整渲染到字符串，成功后才构造响应，带 Content-Length。
 * 渲染中途失败时什么都没有写出，由 SiteExceptionHandler 输出完整的 500 页，不会出现 200 + 半页。
 */
public class SiteHtmlRenderer {

    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    /** 骨架页面没有脚本；后续页面需要脚本或外部资源时随任务评审修改。 */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' http: https:; "
            + "script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'";

    /** 错误模板本身也渲染失败时的兜底：固定文本，不含任何数据。 */
    static final String FALLBACK_ERROR_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"><meta name="robots" content="noindex"><title>页面暂时无法显示</title></head>
            <body><h1>页面暂时无法显示</h1><p>请稍后再试。</p></body>
            </html>
            """;

    private static final Logger log = LoggerFactory.getLogger(SiteHtmlRenderer.class);

    private final Templates templates;
    private final boolean varyUserAgent;

    public SiteHtmlRenderer(Templates templates) {
        this(templates, false);
    }

    /**
     * @param varyUserAgent 页面输出随 {@code User-Agent} 不同（旧配置 {@code mob_status} 为 1 或 2，{@link SiteVariants#dependsOnUserAgent}）：
     *                      每个页面响应带 {@code Vary: User-Agent}，共享缓存不会把手机版给桌面（旧版页面缓存键带 {@code MAC_MOB}）
     */
    public SiteHtmlRenderer(Templates templates, boolean varyUserAgent) {
        this.templates = templates;
        this.varyUserAgent = varyUserAgent;
    }

    /** 整页渲染到字符串。模板或视图模型抛出的任何运行时异常都包装为 {@link PageRenderException}。 */
    public String render(Function<Templates, JteModel> page) {
        try {
            StringOutput out = new StringOutput();
            page.apply(templates).render(out);
            return out.toString();
        } catch (RuntimeException e) {
            throw new PageRenderException(e);
        }
    }

    /** 200 页面。生产缓存 TTL 未定（04 报告第 6.2 节），先发 no-cache。 */
    public ResponseEntity<String> ok(Function<Templates, JteModel> page, boolean noindex) {
        String body = render(page);
        return respond(HttpStatus.OK, body, CacheControl.noCache(), noindex, null, CONTENT_SECURITY_POLICY);
    }

    /**
     * 搜索页（含其 400 页）：noindex、no-store——关键字在 URL 中，不进入共享缓存（09 方案第 7.3 节）。
     * 渲染失败抛 {@link PageRenderException}，由 SiteExceptionHandler 输出 500 页。
     */
    public ResponseEntity<String> noStore(HttpStatus status, Function<Templates, JteModel> page) {
        String body = render(page);
        return respond(status, body, CacheControl.noStore(), true, null, CONTENT_SECURITY_POLICY);
    }

    /**
     * 同源播放器内页（B05，D-B05-1）：noindex、no-store，使用该响应专用的内容安全策略（{@code PlayerFramePolicy}，每个响应一个 nonce）。
     * 其余页面一律是 {@link #CONTENT_SECURITY_POLICY}。
     */
    public ResponseEntity<String> playerFrame(HttpStatus status, Function<Templates, JteModel> page, String policy) {
        String body = render(page);
        return respond(status, body, CacheControl.noStore(), true, null, policy);
    }

    /**
     * 会员页面（B09，18 文档第 8.1 节）：noindex、no-store；内容安全策略在默认策略上把 {@code script-src 'none'} 换成本响应的 nonce，
     * 只放行页面末尾带该 nonce 的同源 {@code member.js}。
     */
    public ResponseEntity<String> member(HttpStatus status, Function<Templates, JteModel> page, String nonce) {
        String body = render(page);
        return respond(status, body, CacheControl.noStore(), true, null, memberPolicy(nonce));
    }

    /** 会员片段与提示页（无脚本）：noindex、no-store，默认策略。 */
    public ResponseEntity<String> memberPlain(HttpStatus status, Function<Templates, JteModel> page) {
        String body = render(page);
        return respond(status, body, CacheControl.noStore(), true, null, CONTENT_SECURITY_POLICY);
    }

    static String memberPolicy(String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9+/]{22}==")) {
            throw new IllegalArgumentException("nonce 不合法");
        }
        return CONTENT_SECURITY_POLICY.replace("script-src 'none'", "script-src 'nonce-" + nonce + "'");
    }

    /** 错误页与维护页：noindex、no-store；503 带 Retry-After。 */
    public ResponseEntity<String> error(HttpStatus status, ErrorPage page, Duration retryAfter) {
        String body;
        try {
            body = render(t -> t.sitePagesError(page));
        } catch (PageRenderException e) {
            log.error("site error page render failed: {}", SafeExceptionLog.typeChain(e));
            body = FALLBACK_ERROR_HTML;
        }
        return respond(status, body, CacheControl.noStore(), true, retryAfter, CONTENT_SECURITY_POLICY);
    }

    private ResponseEntity<String> respond(HttpStatus status, String body, CacheControl cache, boolean noindex,
                                           Duration retryAfter, String policy) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", policy);
        if (varyUserAgent) {
            b.header(HttpHeaders.VARY, HttpHeaders.USER_AGENT);
        }
        if (noindex) {
            b.header("X-Robots-Tag", "noindex");
        }
        if (retryAfter != null) {
            b.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.toSeconds()));
        }
        return b.body(body);
    }
}
