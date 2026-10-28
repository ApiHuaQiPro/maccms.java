package com.maccms.backend.integration.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一次出站请求（B23）。
 *
 * @param purpose 用途标签，只用于日志（如 {@code pay.alipay.order}），{@code [a-z0-9._-]}，1～64 个字符
 * @param method  {@code GET}、{@code POST} 等
 * @param uri     目标地址；凭证不得放进 URL 的用户名密码部分（会被拒绝），查询串里的签名、令牌不会写进日志
 * @param headers 请求头（名称 → 值）；不含 {@code Host}、{@code Content-Length} 等由客户端决定的头
 * @param body    请求体，GET 等不带请求体时为 null
 */
public record OutboundRequest(String purpose, String method, URI uri, Map<String, List<String>> headers, byte[] body) {

    private static final Pattern PURPOSE = Pattern.compile("[a-z0-9._\\-]{1,64}");
    private static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");

    public OutboundRequest {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose 只能是 1～64 个 [a-z0-9._-]");
        }
        method = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHOD.matcher(method).matches()) {
            throw new IllegalArgumentException("method 不合法");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri 不能为空");
        }
        Map<String, List<String>> h = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> h.put(k, List.copyOf(v)));
        }
        headers = Map.copyOf(h);
        body = body == null ? null : body.clone();
    }

    /** GET 请求。 */
    public static OutboundRequest get(String purpose, URI uri) {
        return new OutboundRequest(purpose, "GET", uri, Map.of(), null);
    }

    /** 带请求体的 POST 请求。 */
    public static OutboundRequest post(String purpose, URI uri, String contentType, byte[] body) {
        return new OutboundRequest(purpose, "POST", uri, Map.of("Content-Type", List.of(contentType)), body);
    }

    /** 追加一个请求头，返回新对象。 */
    public OutboundRequest withHeader(String name, String value) {
        Map<String, List<String>> h = new LinkedHashMap<>(headers);
        List<String> values = new ArrayList<>(h.getOrDefault(name, List.of()));
        values.add(value);
        h.put(name, values);
        return new OutboundRequest(purpose, method, uri, h, body);
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    @Override
    public String toString() {
        // 不输出路径、查询串、请求头与请求体
        return "OutboundRequest[" + purpose + " " + method + " " + uri.getHost() + "]";
    }
}
