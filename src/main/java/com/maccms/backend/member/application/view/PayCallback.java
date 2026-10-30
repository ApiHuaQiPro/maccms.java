package com.maccms.backend.member.application.view;

import com.maccms.backend.common.web.PhpInput;
import java.util.Map;

/**
 * 支付平台的回调请求（B24，{@code payment/notify}），由 site 按字节解析后交给 member。
 *
 * @param input ThinkPHP {@code input()}：GET → POST → 路由参数合并（含 {@code pay_type}）
 * @param post  {@code $_POST}（urlencoded 正文；其他类型为空）
 * @param raw   原始正文 {@code php://input}
 */
public record PayCallback(Map<String, PhpInput.Value> input, Map<String, PhpInput.Value> post, byte[] raw) {

    public PayCallback {
        input = Map.copyOf(input);
        post = Map.copyOf(post);
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }
}
