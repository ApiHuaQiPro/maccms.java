package com.maccms.backend.content.application.view;

/**
 * 上传的响应（旧 {@code upload_return}）。
 *
 * @param body 响应正文
 * @param json 后台请求（ThinkPHP {@code json()} 响应）；为 false 时是编辑器格式，旧版 {@code echo} 输出（Content-Type text/html）
 */
public record UploadReply(String body, boolean json) {
}
