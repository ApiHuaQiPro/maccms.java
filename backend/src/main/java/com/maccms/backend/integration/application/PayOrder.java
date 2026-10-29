package com.maccms.backend.integration.application;

/**
 * 下单用的订单字段（{@code mac_order} 一行中旧版 {@code submit} 用到的部分）。
 *
 * @param code  {@code order_code}
 * @param price {@code order_price} 的库内文本（{@code decimal(12,2)}，如 {@code 20.00}；旧版 PDO 取出的就是这个字符串）
 */
public record PayOrder(String code, String price) {
}
