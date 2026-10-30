package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * B11 的评论、留言与 ajax 计数接口共用的请求解析与 JSON 响应（与 B09/B10 会员入口相同的做法，18 文档第 3.1 节）：
 * ThinkPHP {@code input()} 按字节合并查询串、urlencoded 正文与路径参数；{@code Request::isAjax()}；旧版 {@code json()} 的响应头。
 */
final class SiteRequests {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    /** ThinkPHP 5.0 PATH_INFO 参数（Route::parseUrlParams 的 {@code (\w+)\|([^\|]+)}）。 */
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");
    private static final int MAX_FORM_BYTES = 64 * 1024;

    private SiteRequests() {
    }

    /** {@code /键/值} 路径参数（已按 PATH_INFO 解码一次的段）。 */
    static Map<String, String> pathVars(List<String> rest) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (!rest.isEmpty()) {
            Matcher m = PATH_PAIR.matcher(String.join("|", rest));
            while (m.find()) {
                vars.put(m.group(1), m.group(2));
            }
        }
        return vars;
    }

    /** ThinkPHP {@code input()}：GET &lt; POST &lt; 路径参数，按字节解析（只认 urlencoded 正文，上限 64 KB）。 */
    static Map<String, PhpInput.Value> input(HttpServletRequest request, Map<String, String> pathVars) {
        Map<String, PhpInput.Value> get = PhpInput.parseQuery(request.getQueryString());
        Map<String, PhpInput.Value> post = Map.of();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
            if (type.startsWith("application/x-www-form-urlencoded")) {
                post = PhpInput.parse(body(request));
            }
        }
        return PhpInput.merge(get, post, pathVars);
    }

    /** {@code mac_param_url()} 的输入（{@code array_merge(input(), $_REQUEST)}：查询串与 POST 覆盖路径参数），名 → 最后一个值（UTF-8 文本）。 */
    static Map<String, String> request(Map<String, PhpInput.Value> input) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, PhpInput.Value> e : input.entrySet()) {
            if (!e.getValue().array()) {
                m.put(e.getKey(), new String(e.getValue().bytes(), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    /** ThinkPHP {@code Request::isAjax()}：{@code X-Requested-With: XMLHttpRequest}，或参数 {@code _ajax} 为真值。 */
    static boolean isAjax(HttpServletRequest request, Map<String, PhpInput.Value> params) {
        String h = request.getHeader("X-Requested-With");
        if (h != null && h.equalsIgnoreCase("XMLHttpRequest")) {
            return true;
        }
        PhpInput.Value v = params == null ? null : params.get("_ajax");
        return v != null && (v.array() || !phpEmpty(v.bytes()));
    }

    static Map<String, PhpCookies.Value> cookies(HttpServletRequest request) {
        return PhpCookies.parse(Collections.list(request.getHeaders(HttpHeaders.COOKIE)));
    }

    /** PHP {@code !empty(cookie($name))}：不存在、空串与 {@code "0"} 为空；数组形式不为空。 */
    static boolean cookieSet(Map<String, PhpCookies.Value> cookies, String name) {
        PhpCookies.Value v = cookies.get(name);
        return v != null && (v.array() || !phpEmpty(v.bytes()));
    }

    /** PHP setcookie 不接受的 cookie 名（{@code =,; \t\r\n\013\014}）：旧版此时不写 cookie。 */
    static boolean validCookieName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if ("=,; \t\r\n\u000B\f".indexOf(name.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 旧版 {@code json($array)}（ThinkPHP Json 响应：{@code application/json; charset=utf-8}），新版另加 no-store。 */
    static ResponseEntity<byte[]> json(Object body, List<String> cookies) {
        // 旧版的 msg 是 lang(键)：按所选语言包输出（B12 承接 D-B05-8，SiteVariants）
        byte[] bytes = LegacyJson.encode(SiteVariants.localizeMsg(body)).getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        for (String c : cookies) {
            b.header(HttpHeaders.SET_COOKIE, c);
        }
        return b.contentType(JSON).contentLength(bytes.length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("X-Robots-Tag", "noindex").body(bytes);
    }

    static Map<String, Object> reply(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        return m;
    }

    /** 可写数据源关闭时的写入请求：同会员入口（B09），JSON 503。 */
    static ResponseEntity<byte[]> unavailableJson(String msg) {
        byte[] bytes = LegacyJson.encode(reply(503, SiteVariants.text(msg))).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(JSON).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.RETRY_AFTER, "5").body(bytes);
    }

    /** 旧构造函数里的 {@code echo 'comment is close'; exit;}：200 的纯文本（Content-Type 同旧版 text/html）。 */
    static ResponseEntity<byte[]> text(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(HTML_UTF8).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    static boolean phpEmpty(byte[] v) {
        return v.length == 0 || (v.length == 1 && v[0] == '0');
    }

    private static byte[] body(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_FORM_BYTES + 1);
            if (bytes.length > MAX_FORM_BYTES) {
                throw new ApiException(ErrorCode.INVALID_PARAMETER, "请求正文过大");
            }
            return bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
