package com.maccms.backend.openapi.api;

import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.openapi.application.ProvideResponse;
import com.maccms.backend.openapi.application.ProvideService;
import com.maccms.backend.openapi.config.ConditionalOnOpenApiEnabled;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Provide 资源站接口的路由（B06，16 文档第 3.1 节）：{@code /api.php/provide/<操作>[.html|.htm|.shtm|.shtml|.xml][/键/值…][/]}，
 * GET/HEAD/POST。路径按 PATH_INFO 解码一次，后缀与首尾 {@code /} 去掉后按 {@code /} 切分（ThinkPHP 5.0 默认 PATH_INFO 规则，
 * application/config.php:84-86）；操作名只认小写。参数优先级 GET &lt; POST &lt; 路径参数。不认识的操作 404。
 * 参数名含 {@code [}（PHP 数组参数）、路径不能按 UTF-8 解码 → 400。
 */
@Controller
@ConditionalOnOpenApiEnabled
public class ProvideController {

    private static final String PREFIX = "/api.php/provide";
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");

    private final ProvideService service;
    private final ClientIpResolver clientIp;

    public ProvideController(ProvideService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    @RequestMapping(value = {PREFIX + "/**"}, method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<byte[]> provide(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        if (!raw.startsWith(PREFIX + "/") || raw.length() > 2048) {
            return respond(notFound());
        }
        Optional<String> path = percentDecode(raw.substring("/api.php/".length()));
        if (path.isEmpty()) {
            return respond(badRequest());
        }
        String p = trimSlashes(SUFFIX.matcher(path.get()).replaceFirst(""));
        String[] seg = p.split("/", -1);
        if (seg.length < 2 || !seg[0].equals("provide")) {
            return respond(notFound());
        }
        String action = seg[1];
        if (!action.equals(action.toLowerCase(Locale.ROOT))) {
            return respond(notFound());
        }
        List<String> rest = new ArrayList<>(List.of(seg).subList(2, seg.length));
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getKey().indexOf('[') >= 0) {
                return respond(badRequest());
            }
            String[] values = e.getValue();
            params.put(e.getKey(), values.length == 0 ? "" : values[values.length - 1]);
        }
        Map<String, String> merged = ProvideService.mergeParams(params, Map.of(), rest);
        Optional<ProvideResponse> r = service.handle(action, merged, clientIp.resolve(request));
        return respond(r.orElseGet(ProvideController::notFound));
    }

    private static ResponseEntity<byte[]> respond(ProvideResponse r) {
        byte[] body = r.body();
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status())
                .header(HttpHeaders.CONTENT_TYPE, r.contentType())
                .header(HttpHeaders.CACHE_CONTROL, r.cacheControl())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(body.length);
        if (r.status() == 503) {
            b.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return b.body(body);
    }

    private static ProvideResponse notFound() {
        return new ProvideResponse(404, "text/plain; charset=utf-8", "no-store",
                "页面不存在".getBytes(StandardCharsets.UTF_8));
    }

    private static ProvideResponse badRequest() {
        return new ProvideResponse(400, "text/plain; charset=utf-8", "no-store",
                "参数错误".getBytes(StandardCharsets.UTF_8));
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

    /** 路径的百分号解码（{@code +} 不是空格），结果须是合法 UTF-8。 */
    static Optional<String> percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return Optional.of(s);
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%') {
                if (i + 2 >= in.length || Character.digit(in[i + 1], 16) < 0 || Character.digit(in[i + 2], 16) < 0) {
                    return Optional.empty();
                }
                out.write(Character.digit(in[i + 1], 16) * 16 + Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
