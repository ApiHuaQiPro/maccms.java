package com.maccms.backend.openapi.api;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.openapi.application.WechatService;
import com.maccms.backend.openapi.config.ConditionalOnOpenApiEnabled;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 微信公众号入站接口的路由（B27，29 文档第 3 节）：{@code /api.php/wechat[/index][.html|.htm|.shtm|.shtml|.xml][/…][/]}，GET/HEAD/POST，
 * 路径规则同 Provide（B06）。旧控制器只有 {@code index}，其余操作 404；路径参数旧版不读取，忽略。
 *
 * <p>先读原始正文（上限 {@link #MAX_BODY_BYTES}，超过 413），查询串按 PHP {@code $_GET} 解析——不经 {@code getParameterMap}，
 * 表单类型的正文也原样交给消息解析（旧版读 {@code php://input}）。响应 {@code text/html; charset=utf-8}，与旧版相同；另加
 * {@code Content-Security-Policy: default-src 'none'}，回显到页面的消息字段在浏览器里不能执行脚本（D-B27-7）。
 */
@Controller
@ConditionalOnOpenApiEnabled
public class WechatController {

    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String PREFIX = "/api.php/wechat";
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");

    private final WechatService service;

    public WechatController(WechatService service) {
        this.service = service;
    }

    @RequestMapping(value = {PREFIX, PREFIX + ".*", PREFIX + "/**"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<byte[]> wechat(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        if (raw.length() > 2048) {
            return text(404, "页面不存在");
        }
        Optional<String> path = ProvideController.percentDecode(raw.substring("/api.php/".length()));
        if (path.isEmpty()) {
            return text(400, "参数错误");
        }
        String p = trimSlashes(SUFFIX.matcher(path.get()).replaceFirst(""));
        String[] seg = p.split("/", -1);
        if (!seg[0].equals("wechat") || (seg.length > 1 && !seg[1].equals("index"))) {
            return text(404, "页面不存在");
        }
        byte[] body = body(request);
        if (body == null) {
            return text(413, "请求正文过大");
        }
        WechatService.Result r;
        try {
            r = service.handle(PhpInput.parseQuery(request.getQueryString()), body);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.SERVICE_UNAVAILABLE) {
                return text(503, "服务暂时不可用");
            }
            throw e;
        }
        return ResponseEntity.status(r.status())
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .contentLength(r.body().length)
                .body(r.body());
    }

    private static ResponseEntity<byte[]> text(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length);
        if (status == 503) {
            b.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return b.body(bytes);
    }

    /** 正文；超过上限时 null。 */
    private static byte[] body(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            return bytes.length > MAX_BODY_BYTES ? null : bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String trimSlashes(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '/') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '/') {
            b--;
        }
        return s.substring(a, b);
    }
}
