package com.maccms.backend.member.application.view;

import java.util.function.UnaryOperator;

/**
 * 下单时由 site 提供的请求环境（B24）。
 *
 * @param clientIp  客户端 IP（旧 {@code mac_get_client_ip()}，微信 {@code spbill_create_ip}）
 * @param notifyUrl 支付方式 → 回调地址（站点 {@code base-url} + {@code index.php/payment/notify/pay_type/<方式>}）
 * @param w3        部署级 W3（支付网关跳转）是否放行；关闭时支付宝、码支付、Zhapay 按"未开启"处理
 */
public record PaymentContext(String clientIp, UnaryOperator<String> notifyUrl, boolean w3) {
}
