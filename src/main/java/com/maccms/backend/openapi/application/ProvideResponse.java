package com.maccms.backend.openapi.application;

import java.nio.charset.StandardCharsets;

/**
 * Provide 的一次响应（B06，16 文档第 3.2、3.5 节）。
 *
 * @param status       HTTP 状态码
 * @param contentType  Content-Type（含 charset）
 * @param cacheControl Cache-Control
 * @param body         正文字节（UTF-8）
 */
public record ProvideResponse(int status, String contentType, String cacheControl, byte[] body) {

    static final String TEXT = "text/plain; charset=utf-8";
    static final String JSON = "application/json; charset=utf-8";
    static final String XML = "text/xml; charset=utf-8";
    static final String NO_STORE = "no-store";

    public ProvideResponse {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    static ProvideResponse text(int status, String text) {
        return new ProvideResponse(status, TEXT, NO_STORE, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 旧版 {@code echo 'closed'}（Provide.php:24）。 */
    static ProvideResponse closed() {
        return text(200, "closed");
    }

    /** 旧版 {@code lang('api/auth_err')}（application/lang/zh-cn.php:1919）；新版 403（16 文档 D-B06-5）。 */
    static ProvideResponse denied() {
        return text(403, "域名未授权");
    }

    static ProvideResponse badRequest() {
        return text(400, "参数错误");
    }

    static ProvideResponse unavailable() {
        return text(503, "服务暂时不可用");
    }

    /** {@code comment}、{@code index}：旧方法体为空，输出空响应。 */
    static ProvideResponse empty() {
        return new ProvideResponse(200, TEXT, NO_STORE, new byte[0]);
    }
}
