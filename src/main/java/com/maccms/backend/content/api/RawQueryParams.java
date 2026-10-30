package com.maccms.backend.content.api;

import com.maccms.backend.content.application.VideoFilter;
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
 * content 列表接口的查询参数（视频列表见 09 方案第 4.3 节）。值是按 application/x-www-form-urlencoded 解码<b>一次</b>的结果
 * （{@code +} 为空格），不再二次解码；不记录、不回显任何值。
 *
 * <p>正常情况下取容器解析的参数（与契约 1.0.0 时 {@code @RequestParam} 相同的来源），另扫描一遍原始查询串，统计
 * 新参数的出现次数并检查其百分号编码是否为合法 UTF-8（容器也可能把非法字节替换成 U+FFFD，或丢弃该参数）。
 *
 * <p>Tomcat 11 遇到任一参数的编码不合法时拒绝解析整个查询串（{@code InvalidParameterException}，
 * 是 {@link IllegalStateException}），1.0.0 时这会成为 500。为让 {@code keyword=%FF} 等得到 400：只有当不合法的编码全部
 * 出现在这些新参数（{@link #FILTER_PARAMS}）中时，才改用本类按同一规则自行解析的结果；不合法编码出现在既有参数、未知参数或键名中时原样抛出
 * （与 1.0.0 相同）。这一回退只用于视频列表（{@link #forVideoList}）；文章/专题/演员/角色/网址/友链列表（{@link #of}）
 * 遇到任何非法编码都原样抛出，与契约 1.2.0 写明的 500 一致（B04 之前它们与视频列表共用回退集合，{@code area=%FF} 等会被当成
 * 字面值 {@code "null"}）。
 */
final class RawQueryParams {

    /** 契约 1.1.0 起新增的搜索/筛选参数（1.3.0 再增 letter、state、version、actor、director、tag、name、ids）。 */
    static final Set<String> FILTER_PARAMS = Set.copyOf(VideoFilter.PARAMETERS);

    /** 每个键的各次出现的值；自行解析时值为 {@code null} 表示该次出现的值编码不合法。 */
    private final Map<String, List<String>> values;
    /** 原始查询串扫描结果：每个键（解码后）的各次出现，含编码不合法的出现。 */
    private final Scan scan;

    private RawQueryParams(Map<String, List<String>> values, Scan scan) {
        this.values = values;
        this.scan = scan;
    }

    /** 视频列表：非法编码只出现在 {@link #FILTER_PARAMS} 中时改用自行解析的结果（由 {@link #filterParam} 报 400）。 */
    static RawQueryParams forVideoList(HttpServletRequest request) {
        return of(request, FILTER_PARAMS);
    }

    /** 其他列表：只取容器解析的参数，非法编码由容器拒绝（500）。 */
    static RawQueryParams of(HttpServletRequest request) {
        return of(request, Set.of());
    }

    private static RawQueryParams of(HttpServletRequest request, Set<String> fallbackParams) {
        Scan scan = scan(request.getQueryString(), fallbackParams);
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

    private static Scan scan(String query, Set<String> fallbackParams) {
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
                if (key == null || !fallbackParams.contains(key)) {
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
