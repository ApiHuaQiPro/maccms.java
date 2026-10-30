package com.maccms.backend.integration.application;

import com.maccms.backend.common.web.PhpInput;
import java.util.Map;

/**
 * 支付平台的回调请求。
 *
 * @param input ThinkPHP {@code input()}：GET → POST → 路由参数合并（支付宝用）
 * @param post  {@code $_POST}（码支付、Zhapay 用）
 * @param raw   原始正文 {@code php://input}（微信用）
 */
public record PayNotify(Map<String, PhpInput.Value> input, Map<String, PhpInput.Value> post, byte[] raw) {

    public PayNotify {
        input = Map.copyOf(input);
        post = Map.copyOf(post);
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }
}
