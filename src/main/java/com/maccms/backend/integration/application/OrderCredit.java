package com.maccms.backend.integration.application;

/** 到账（旧 {@code model('Order')->notify($order_code, $pay_type)}），返回旧版的 {@code code}：1 成功或已支付过，大于 1 失败。 */
@FunctionalInterface
public interface OrderCredit {

    int credit(String orderCode, String payType);
}
