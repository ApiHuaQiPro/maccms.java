package com.maccms.backend.integration.oauth;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 表单与查询串的编码、解析（UTF-8）。 */
final class Form {

    private Form() {
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** {@code a=1&b=2}；解码失败的项跳过，同名取第一个。 */
    static Map<String, String> parse(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.putIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // 跳过
            }
        }
        return out;
    }
}
