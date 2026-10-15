package com.maccms.backend.content.api;

import com.maccms.backend.content.application.VideoFilter.RawParam;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET /api/v1/videos 的查询参数（09 方案第 4.3 节）。值是按 application/x-www-form-urlencoded 解码<b>一次</b>的结果
 * （{@code +} 为空格），不再二次解码；不记录、不回显任何值。
 *
 * <p>正常情况下取容器解析的参数（与契约 1.0.0 时 {@code @RequestParam} 相同的来源），另扫描一遍原始查询串，统计
 * 新参数的出现次数并检查其百分号编码是否为合法 UTF-8（容器也可能把非法字节替换成 U+FFFD，或丢弃该参数）。
 *
 * <p>Tomcat 11 遇到任一参数的编码不合法时拒绝解析整个查询串（{@code InvalidParameterException}，
 * 是 {@link IllegalStateException}），1.0.0 时这会成为 500。为让 {@code keyword=%FF} 等得到 400：只有当不合法的编码全部
 * 出现在 6 个新参数中时，才改用本类按同一规则自行解析的结果；不合法编码出现在既有参数、未知参数或键名中时原样抛出
 * （与 1.0.0 相同）。
 */
final class RawQueryParams {

    /** 契约 1.1.0 新增的参数。 */
    static final Set<String> FILTER_PARAMS = Set.of("keyword", "area", "lang", "year", "level", "classes");

    /** 每个键的各次出现的值；自行解析时值为 {@code null} 表示该次出现的值编码不合法。 */
    private final Map<String, List<String>> values;
    /** 原始查询串扫描结果：每个键（解码后）的各次出现，含编码不合法的出现。 */
    private final Scan scan;

    private RawQueryParams(Map<String, List<String>> values, Scan scan) {
        this.values = values;
        this.scan = scan;
    }

    static RawQueryParams of(HttpServletRequest request) {
        Scan scan = scan(request.getQueryString());
        Map<String, String[]> container;
        try {
            container = request.getParameterMap();
        } catch (IllegalStateException e) {
            if (!scan.anyMalformed || scan.malformedOutsideFilterParams) {
                throw e;
            }
            return new RawQueryParams(scan.values, scan);
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        container.forEach((k, v) -> values.put(k, List.of(v)));
        return new RawQueryParams(values, scan);
    }

    /** 既有参数：多次出现时与 Spring 把 String[] 转成 String 的结果相同（逗号连接），按 1.0.0 的规则判错。 */
    String joined(String name) {
        List<String> v = values.get(name);
        return v == null || v.isEmpty() ? null : String.join(",", v);
    }

    /** 新参数：出现次数取容器与原始查询串中较大者。 */
    RawParam filterParam(String name) {
        List<String> v = values.get(name);
        List<String> raw = scan.values.get(name);
        int count = Math.max(v == null ? 0 : v.size(), raw == null ? 0 : raw.size());
        String value = v != null && v.size() == 1 ? v.get(0) : null;
        return new RawParam(count, value, scan.malformed.getOrDefault(name, false));
    }

    private static final class Scan {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        final Map<String, Boolean> malformed = new LinkedHashMap<>();
        boolean anyMalformed;
        boolean malformedOutsideFilterParams;
    }

    private static Scan scan(String query) {
        Scan s = new Scan();
        if (query == null) {
            return s;
        }
        for (String part : query.split("&", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = decode(eq < 0 ? part : part.substring(0, eq));
            String value = eq < 0 ? "" : decode(part.substring(eq + 1));
            if (key == null || value == null) {
                s.anyMalformed = true;
                if (key == null || !FILTER_PARAMS.contains(key)) {
                    s.malformedOutsideFilterParams = true;
                    continue;
                }
                s.malformed.put(key, true);
            }
            if (key.isEmpty()) {
                continue;
            }
            s.values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return s;
    }

    /** application/x-www-form-urlencoded 解码一次（{@code +} 为空格）；编码不合法或不是合法 UTF-8 时返回 {@code null}。 */
    static String decode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
