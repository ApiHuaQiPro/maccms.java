package com.maccms.backend.integration.application;

/**
 * 下单请求里与支付方式有关的输入。
 *
 * @param paytype  旧 {@code $param['paytype']} 的原文（码支付、Zhapay 的通道；不存在时为 null）
 * @param clientIp 旧 {@code mac_get_client_ip()}（微信 {@code spbill_create_ip}）
 */
public record PayRequest(String paytype, String clientIp) {
}
