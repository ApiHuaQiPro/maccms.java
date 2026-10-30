package com.maccms.backend.install.web;

import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.install.web.jte.Templates;
import com.maccms.backend.install.web.view.JumpPage;
import gg.jte.models.runtime.JteModel;
import gg.jte.output.StringOutput;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 安装向导的响应（B22，31 文档第 7 节），做法同后台（21 文档第 4.1、8 节）：
 * <ul>
 * <li>{@link #success}/{@link #error}：旧 ThinkPHP {@code Jump::success/error}。Ajax 请求返回 {@code {"code","msg","data","url","wait"}}
 * （编码同 PHP {@code json_encode(…, JSON_UNESCAPED_UNICODE)}）；否则返回跳转提示页。JSON 的 {@code msg} 会被页面脚本当作 HTML 显示
 * （layer.msg），新版把其中的 {@code < > & " '} 转义；跳转页按旧版 {@code strip_tags} 去掉标签后再由模板转义。</li>
 * <li>页面先完整渲染到字符串再响应；不缓存、{@code nosniff}、{@code noindex}，带内容安全策略 {@link #CONTENT_SECURITY_POLICY}。</li>
 * </ul>
 */
public class InstallResponses {

    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    /**
     * 只允许本站脚本（没有内联脚本）；layui 的模块加载与模板用到 {@code new Function}，同后台保留 {@code 'unsafe-eval'}；旧模板有内联样式，
     * 保留 {@code 'unsafe-inline'} 样式；不允许被嵌入框架。
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "frame-src 'none'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'; object-src 'none'";

    private final Templates templates;
    private final String assetsPath;

    public InstallResponses(Templates templates, String assetsPath) {
        this.templates = templates;
        this.assetsPath = assetsPath;
    }

    public ServerResponse page(Function<Templates, JteModel> page) {
        StringOutput out = new StringOutput();
        page.apply(templates).render(out);
        return html(HttpStatus.OK, out.toString());
    }

    /** 旧 {@code $this->success($msg, $url)}。 */
    public ServerResponse success(boolean ajax, String msg, String url) {
        return jump(ajax, true, msg, url);
    }

    /** 旧 {@code $this->error($msg)}：Ajax 时 {@code url} 为空串，否则为 {@code javascript:history.back(-1);}。 */
    public ServerResponse error(boolean ajax, String msg) {
        return jump(ajax, false, msg, ajax ? "" : JumpPage.BACK);
    }

    /** 旧版 {@code echo} 的一段文字（安装锁提示，install.php:30-33）。 */
    public ServerResponse text(String body) {
        return ServerResponse.ok()
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(body);
    }

    private ServerResponse jump(boolean ajax, boolean success, String msg, String url) {
        if (ajax) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", success ? 1 : 0);
            m.put("msg", escapeHtml(msg));
            m.put("data", "");
            m.put("url", url);
            m.put("wait", 3);
            return ServerResponse.ok()
                    .contentType(JSON_UTF8)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Robots-Tag", "noindex")
                    .body(LegacyJson.encode(m));
        }
        JumpPage p = new JumpPage(success, stripTags(msg), url, 3, assetsPath);
        return page(t -> t.installPagesJump(p));
    }

    static ServerResponse html(HttpStatus status, String body) {
        return ServerResponse.status(status)
                .contentType(HTML_UTF8)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "same-origin")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .body(body);
    }

    /** 旧模板 {@code strip_tags($msg)} 的简化：去掉 {@code <…>}（提示里只有语言包文字与新版的固定文字）。 */
    static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>?", "");
    }

    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#039;");
    }
}
