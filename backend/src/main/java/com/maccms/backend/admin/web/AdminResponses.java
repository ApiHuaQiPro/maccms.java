package com.maccms.backend.admin.web;

import com.maccms.backend.admin.web.jte.Templates;
import com.maccms.backend.admin.web.view.JumpPage;
import com.maccms.backend.admin.web.view.NoticePage;
import com.maccms.backend.common.web.LegacyJson;
import gg.jte.models.runtime.JteModel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 后台响应（21 文档第 4.1、8 节）。
 * <ul>
 * <li>{@link #success}/{@link #error}：旧 ThinkPHP {@code Jump::success/error}（thinkphp/library/traits/controller/Jump.php）。Ajax 请求
 * （{@code X-Requested-With: XMLHttpRequest}，或参数 {@code _ajax} 为真）返回 {@code {"code","msg","data","url","wait"}}，编码同 PHP
 * {@code json_encode(…, JSON_UNESCAPED_UNICODE)}，{@code Content-Type: application/json; charset=utf-8}；否则返回跳转提示页。</li>
 * <li>{@link #page}：Ajax 请求拿到的是整页 HTML 的 JSON 字符串（旧版控制器返回字符串、Ajax 请求的默认输出类型是 json；
 * B13 的 {@code j-select} 弹窗依赖这一点）。</li>
 * <li>全部不缓存（no-store）、{@code nosniff}、{@code X-Robots-Tag: noindex}；HTML 带内容安全策略 {@link #CONTENT_SECURITY_POLICY}。</li>
 * </ul>
 */
public class AdminResponses {

    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    /**
     * 后台页面的内容安全策略（21 文档第 8.2 节）：脚本只能来自本站文件，不允许内联脚本。旧版的 static/js/admin_common.js 与 layui 原样复用：
     * <ul>
     * <li>它们用到 {@code new Function}（锁屏、laytpl），保留 {@code 'unsafe-eval'}；</li>
     * <li>旧模板里大量 {@code href="javascript:;"}、{@code javascript:void(0);} 的空链接，layer 弹层的 iframe 带
     * {@code onload="this.className='';"}：用 {@code 'unsafe-hashes'} 只放行这几段固定代码的哈希（Chromium 对 {@code javascript:} 地址按
     * 整个地址计算），其他任何内联事件与 {@code javascript:} 地址仍被拦截；</li>
     * <li>layer 与旧模板有内联样式，保留 {@code 'unsafe-inline'} 样式；快捷菜单可以配置外部地址并在标签页里打开，frame 放行 http/https。</li>
     * </ul>
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self' 'unsafe-eval' 'unsafe-hashes' "
            // javascript:;
            + "'sha256-lfXlPY3+MCPOPb4mrw1Y961+745U3WlDQVcOXdchSQc=' "
            // javascript:void(0);
            + "'sha256-kbHtQyYDQKz4SWMQ8OHVol3EC0t3tHEJFPCSwNG9NxQ=' "
            // javascript:void(0);return false;（快捷菜单分隔项，AdminMenus.QUICK_TITLE_URL）
            + "'sha256-Y7jtmvM4h54PAN6iU0KiiU61MwAKR/V1v/lJRAeK0RM=' "
            // this.className='';（layui layer.js 弹层 iframe 的 onload）
            + "'sha256-cC/mporZDtDF8EAf6H9nfBNfMbsO2Uc77gsmvyYdR74='; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "frame-src 'self' http: https:; frame-ancestors 'self'; form-action 'self'; base-uri 'none'; object-src 'none'";

    private final AdminHtmlRenderer renderer;
    private final String assetsPath;

    public AdminResponses(AdminHtmlRenderer renderer, String assetsPath) {
        this.renderer = renderer;
        this.assetsPath = assetsPath;
    }

    /** 整页；Ajax 请求时是该 HTML 的 JSON 字符串。 */
    public ResponseEntity<String> page(boolean ajax, HttpStatus status, Function<Templates, JteModel> page) {
        String html = renderer.render(page);
        if (ajax) {
            return json(status, LegacyJson.encode(html));
        }
        return html(status, html);
    }

    /** 旧 {@code $this->success($msg)}：{@code url} 为来源页或当前动作的地址。 */
    public ResponseEntity<String> success(boolean ajax, String msg, String url) {
        return jump(ajax, true, msg, url);
    }

    /** 旧 {@code $this->success($msg, null, $data)}（B13 分类扩展字典）：Ajax 时 data 原样编码；页面时与 {@link #success} 相同。 */
    public ResponseEntity<String> success(boolean ajax, String msg, String url, Object data) {
        if (ajax) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", 1);
            m.put("msg", msg);
            m.put("data", data);
            m.put("url", url);
            m.put("wait", 3);
            return json(HttpStatus.OK, LegacyJson.encode(m));
        }
        return jump(false, true, msg, url);
    }

    /** 旧 {@code $this->error($msg)}：Ajax 时 {@code url} 为空串，否则为 {@code javascript:history.back(-1);}。 */
    public ResponseEntity<String> error(boolean ajax, String msg) {
        return jump(ajax, false, msg, ajax ? "" : JumpPage.BACK);
    }

    /** 302（旧 {@code $this->redirect()} 与 Begin 行为里的 {@code header('Location: …')}）。 */
    public ResponseEntity<String> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body("");
    }

    /**
     * 不跳转的提示页（后续批次的动作、页面不存在）；Ajax 时为 {@code code=0} 的 JSON。后续批次的提示对 Ajax 用 200（旧后台脚本只在 200 时
     * 显示 msg），页面不存在仍是 404。
     */
    public ResponseEntity<String> notice(boolean ajax, HttpStatus status, String title, String msg) {
        if (ajax) {
            return json(status == HttpStatus.NOT_FOUND ? status : HttpStatus.OK, legacyJson(0, msg, ""));
        }
        return html(status, renderer.render(t -> t.adminPagesNotice(new NoticePage(title, msg))));
    }

    /** 旧 {@code return json($res)}：按 PHP {@code json_encode(…, JSON_UNESCAPED_UNICODE)} 编码（B13 updateToday）。 */
    public ResponseEntity<String> rawJson(Object value) {
        return json(HttpStatus.OK, LegacyJson.encode(value));
    }

    /** 已编码好的 JSON 正文（上传的 {@code json()} 响应）。 */
    public ResponseEntity<String> jsonBody(String body) {
        return json(HttpStatus.OK, body);
    }

    /** 旧版 {@code echo} 出来的整段 HTML（B13 批量处理等逐页推进的输出）：带与页面相同的安全头。 */
    public ResponseEntity<String> echoed(String body) {
        return html(HttpStatus.OK, body);
    }

    /** 纯文本（旧 {@code echo}，Content-Type 为 text/html）。 */
    public ResponseEntity<String> text(String body) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(body);
    }

    /** 跳转提示页的 HTML（旧 {@code error()} 在已输出的内容后面接着输出时用）。 */
    public String jumpHtml(boolean success, String msg, String url) {
        return renderer.render(t -> t.adminPagesJump(new JumpPage(success, msg, url, 3, assetsPath)));
    }

    private ResponseEntity<String> jump(boolean ajax, boolean success, String msg, String url) {
        if (ajax) {
            return json(HttpStatus.OK, legacyJson(success ? 1 : 0, msg, url));
        }
        return html(HttpStatus.OK, renderer.render(t -> t.adminPagesJump(new JumpPage(success, msg, url, 3, assetsPath))));
    }

    static String legacyJson(int code, String msg, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        m.put("data", "");
        m.put("url", url);
        m.put("wait", 3);
        return LegacyJson.encode(m);
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(JSON_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(body);
    }

    static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Referrer-Policy", "same-origin")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .body(body);
    }
}
