package com.maccms.backend.staticgen.web;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.staticgen.application.TimmingService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 定时任务入口 {@code api.php/timming/index}（旧 api/controller/Timming.php，B16，25 文档第 7 节）。先做部署级鉴权（{@link TimmingAccess}，
 * D2 ③）：未通过时返回 403 固定提示，不读取任务表、不执行任何任务、不回写 {@code timming.php}（含带 {@code enforce=1} 的请求）。
 * 通过后按旧版执行（{@link TimmingService}）。参数同旧 {@code input('get.', '', 'trim,urldecode')}：只看查询串，先 trim 再 urldecode。
 * 只在 {@code maccms.timming.enabled=true} 时注册（默认关闭）。
 */
@Controller
@ConditionalOnProperty(prefix = "maccms.timming", name = "enabled", havingValue = "true")
public class TimmingController {

    /** 未通过鉴权时的固定提示（不含请求内容）。 */
    public static final String REFUSED = "定时任务入口只接受本机请求或携带部署口令的请求，未执行任何任务";
    static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    /** 输出里有旧版的内联样式（{@code <style>}），没有脚本。 */
    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final TimmingAccess access;
    private final TimmingService service;
    private final TimmingService.TaskRunner tasks;

    public TimmingController(TimmingAccess access, TimmingService service, TimmingService.TaskRunner tasks) {
        this.access = access;
        this.service = service;
        this.tasks = tasks;
    }

    @RequestMapping(value = {"/api.php/timming", "/api.php/timming/", "/api.php/timming/index", "/api.php/timming/index/",
            "/api.php/timming/index.html"}, method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<String> index(HttpServletRequest request) {
        if (!access.allowed(request)) {
            return respond(HttpStatus.FORBIDDEN, REFUSED);
        }
        Map<String, PhpInput.Value> get = PhpInput.parseQuery(request.getQueryString());
        String body = service.run(param(get, "name"), param(get, "enforce"), tasks);
        return respond(HttpStatus.OK, body);
    }

    private static ResponseEntity<String> respond(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("X-Robots-Tag", "noindex")
                .header("Content-Security-Policy", CSP).body(body);
    }

    /** {@code trim} 后 {@code urldecode}（旧过滤器顺序）；数组或缺失为空串。 */
    static String param(Map<String, PhpInput.Value> get, String name) {
        PhpInput.Value v = get.get(name);
        if (v == null || v.array()) {
            return "";
        }
        return urldecode(phpTrim(v.bytes()));
    }

    private static byte[] phpTrim(byte[] b) {
        int s = 0;
        int e = b.length;
        while (s < e && " \t\n\r\0\u000B".indexOf(b[s]) >= 0) {
            s++;
        }
        while (e > s && " \t\n\r\0\u000B".indexOf(b[e - 1]) >= 0) {
            e--;
        }
        return java.util.Arrays.copyOfRange(b, s, e);
    }

    /** PHP {@code urldecode}：{@code +} 为空格，{@code %XX}（十六进制）解码，其余字节原样。 */
    static String urldecode(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == '+') {
                out.write(' ');
            } else if (c == '%' && i + 2 < b.length && hex(b[i + 1]) >= 0 && hex(b[i + 2]) >= 0) {
                out.write(hex(b[i + 1]) * 16 + hex(b[i + 2]));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        return Character.digit((char) (b & 0xff), 16);
    }
}
