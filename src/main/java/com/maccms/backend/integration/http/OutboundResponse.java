package com.maccms.backend.integration.http;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 出站请求的响应（B23）。跳转已由客户端跟随，这里是最后一跳的结果。
 *
 * @param status  HTTP 状态码
 * @param headers 响应头（名称小写）
 * @param body    响应体（不超过上限）
 * @param host    最后一跳的目标主机（{@code host[:port]}）
 */
public record OutboundResponse(int status, Map<String, List<String>> headers, byte[] body, String host) {

    public OutboundResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** 响应体按给定编码解码。 */
    public String bodyText(Charset charset) {
        return new String(body, charset);
    }

    /** 第一个同名响应头（名称不分大小写）。 */
    public Optional<String> header(String name) {
        List<String> v = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
    }

    @Override
    public String toString() {
        // 不输出响应头与响应体（可能含令牌）
        return "OutboundResponse[" + status + " " + host + " " + body.length + " bytes]";
    }
}
