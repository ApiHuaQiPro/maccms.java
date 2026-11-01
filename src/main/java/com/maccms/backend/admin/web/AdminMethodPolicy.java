package com.maccms.backend.admin.web;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 后台路径受理的 HTTP 方法（APPK-2 接口契约 ADM-6 / {@code x-method-policy}）：契约登记的 {@code 控制器/动作} 只受理表中列出的方法，
 * 其他方法（含 HEAD、PUT、DELETE，以及只列 POST 的动作收到的 GET）→ 405 + {@code Allow}，固定正文，不读会话、不校验令牌、不执行。
 * 未登记的后台路径维持基线（GET、HEAD、POST）。与 PHP 的差异（PHP 不区分方法）登记为 METHOD-1。
 */
final class AdminMethodPolicy {

    /** 未登记路径的受理方法（基线 {@link AdminController} 的映射）。 */
    static final List<String> BASELINE = List.of("GET", "HEAD", "POST");
    static final String BODY = "Method Not Allowed";

    private static final List<String> GET = List.of("GET");
    private static final List<String> POST = List.of("POST");
    private static final List<String> GET_POST = List.of("GET", "POST");
    private static final Map<String, List<String>> ROWS = rows();

    private AdminMethodPolicy() {
    }

    private static Map<String, List<String>> rows() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("database/index", GET);
        m.put("database/optimize", GET_POST);
        m.put("database/repair", GET_POST);
        m.put("database/sql", GET_POST);
        m.put("database/import", POST);
        m.put("database/export", GET_POST);
        m.put("database/del", GET_POST);
        m.put("database/recover", POST);
        m.put("database/acknowledge", POST);
        m.put("template/info", GET_POST);
        m.put("template/del", GET_POST);
        m.put("template/wizard", GET);
        m.put("template/ads", GET);
        for (String a : List.of("index", "map", "rss", "type", "topic_index", "topic_info", "info", "label", "make", "opt")) {
            m.put("make/" + a, GET_POST);
        }
        m.put("index/clear", GET_POST);
        m.put("collect/api", GET);
        m.put("collect/test", GET_POST);
        m.put("collect/timing", GET);
        m.put("images/sync", GET_POST);
        return Map.copyOf(m);
    }

    /** 该 {@code 控制器/动作} 受理的方法。 */
    static List<String> allowed(String code) {
        return ROWS.getOrDefault(code, BASELINE);
    }

    /** 是否受契约方法表约束。 */
    static boolean registered(String code) {
        return ROWS.containsKey(code);
    }

    /** 方法不受理时的 405 响应；受理时为 null。 */
    static ResponseEntity<String> check(String code, String method) {
        List<String> allow = allowed(code);
        if (allow.contains(method == null ? "" : method.toUpperCase(Locale.ROOT))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, String.join(", ", allow))
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(BODY);
    }

    /** 测试用：登记的全部 {@code 控制器/动作}。 */
    static Set<String> codes() {
        return ROWS.keySet();
    }
}
