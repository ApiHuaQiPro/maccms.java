package com.maccms.backend.integration.application;

import java.util.function.UnaryOperator;

/**
 * 在线支付的四种方式（B24，E04 下单、E05 回调、W3 网关跳转；docs/architecture/33-b24-payment.md）。实现在 {@code integration.pay}，
 * 其他模块只经本接口使用：订单的查找与到账写库由调用方（member）负责，这里只做旧版 {@code common/extend/pay/*.php} 的参数、签名、
 * 网关地址、统一下单请求与回调验签。支付方式名是旧版的类名小写：{@code alipay}、{@code codepay}、{@code weixin}、{@code zhapay}。
 */
public interface PaymentGateways {

    /** 四种方式，顺序同旧 {@code mac_extends_list('pay')}（文件名排序）。 */
    java.util.List<String> METHODS = java.util.List.of("alipay", "codepay", "weixin", "zhapay");

    /**
     * 旧版对 {@code $GLOBALS['config']['pay'][$payment]['appid']} 的判断（{@code index/controller/User.php:503}、
     * {@code Payment.php:19}）与类是否存在（{@code class_exists}）的组合结果。
     */
    enum Availability {
        /** {@code appid == ''}（含配置里没有这一项）："该支付选项未开启！"。 */
        NOT_OPEN,
        /**
         * appid 判断通过但没有对应的类：{@code pay} 段里的标量项（{@code min}、{@code scale}）。PHP 7 对字符串取 {@code ['appid']} 得到
         * 首字符，判断通过后 {@code class_exists} 失败（gopay 输出空页面，notify 输出"未找到支付选项！"）。
         */
        NO_METHOD,
        /** 四种方式之一，且 appid 非空。 */
        AVAILABLE
    }

    Availability availability(String payment);

    /** 是否会让浏览器跳到第三方网关（W3：支付宝、码支付、Zhapay）；微信是本站展示二维码，不属于 W3。 */
    boolean browserRedirect(String payment);

    /**
     * 下单（旧 {@code submit($user, $order, $param)}）。调用方已确认 {@link #availability} 为 {@link Availability#AVAILABLE}。
     *
     * @param notifyUrl 支付方式 → 回调/返回地址（旧版 {@code http_type . HTTP_HOST . '/index.php/payment/notify/pay_type/<方式>'}）
     */
    PayStart start(String payment, PayOrder order, PayRequest request, UnaryOperator<String> notifyUrl);

    /**
     * 回调（旧 {@code <方式>::notify()}）：验签通过后调用 {@code credit}，按旧版把结果写成响应正文。调用方已确认
     * {@link #availability} 为 {@link Availability#AVAILABLE}。
     */
    String notify(String payment, PayNotify notify, OrderCredit credit);
}
