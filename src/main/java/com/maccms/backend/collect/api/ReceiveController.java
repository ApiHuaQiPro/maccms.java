package com.maccms.backend.collect.api;

import com.maccms.backend.collect.application.ReceiveService;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Receive 入站接口的路由（B19，27 文档第 5 节）：{@code /api.php/receive/<操作>[.html|.htm|.shtm|.shtml|.xml][/键/值…][/]}，GET/HEAD/POST，
 * 路径规则同 Provide（B06）。入库要写库，所以只在可写数据源打开时注册；关闭时这条路径 404。响应同旧版：{@code text/html; charset=utf-8} 的
 * JSON 文字。POST 只认 urlencoded 正文（上限 {@link #MAX_BODY_BYTES}，超过 413）。
 */
@Controller
@ConditionalOnWriteEnabled
public class ReceiveController {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String PREFIX = "/api.php/receive";
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");
    /** ThinkPHP 5.0 PATH_INFO 参数（Route::parseUrlParams 的 {@code (\w+)\|([^\|]+)}）。 */
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");

    private final ReceiveService service;

    public ReceiveController(ReceiveService service) {
        this.service = service;
    }

    @RequestMapping(value = {PREFIX + "/**"}, method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<byte[]> receive(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        if (!raw.startsWith(PREFIX + "/") || raw.length() > 2048) {
            return text(404, "页面不存在");
        }
        Optional<String> path = percentDecode(raw.substring("/api.php/".length()));
        if (path.isEmpty()) {
            return text(400, "参数错误");
        }
        String p = trimSlashes(SUFFIX.matcher(path.get()).replaceFirst(""));
        String[] seg = p.split("/", -1);
        if (seg.length < 2 || !seg[0].equals("receive")) {
            return text(404, "页面不存在");
        }
        String action = seg[1];
        if (!action.equals(action.toLowerCase(Locale.ROOT))) {
            return text(404, "页面不存在");
        }
        Map<String, String> route = new LinkedHashMap<>();
        Matcher m = PATH_PAIR.matcher(String.join("|", List.of(seg).subList(2, seg.length)));
        while (m.find()) {
            route.put(m.group(1), m.group(2));
        }
        Map<String, PhpInput.Value> post = Map.of();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
            if (type.startsWith("application/x-www-form-urlencoded")) {
                byte[] body = body(request);
                if (body == null) {
                    return text(413, "请求正文过大");
                }
                post = PhpInput.parse(body);
            }
        }
        Map<String, PhpInput.Value> params = PhpInput.merge(PhpInput.parseQuery(request.getQueryString()), post, route);
        return service.handle(action, params)
                .map(r -> respond(r.status(), r.contentType(), r.body()))
                .orElseGet(() -> text(404, "页面不存在"));
    }

    private static ResponseEntity<byte[]> respond(int status, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length);
        if (status == 503) {
            b.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return b.body(bytes);
    }

    private static ResponseEntity<byte[]> text(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length).body(bytes);
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

    /** 路径的百分号解码（{@code +} 不是空格），结果须是合法 UTF-8（同 Provide）。 */
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
