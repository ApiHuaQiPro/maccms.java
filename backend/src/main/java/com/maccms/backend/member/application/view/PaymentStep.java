package com.maccms.backend.member.application.view;

import java.util.List;
import java.util.Optional;

/** {@code user/gopay} 的结果（B24，docs/architecture/33-b24-payment.md 第 2 节），由 site 按旧版输出。 */
public sealed interface PaymentStep {

    /** ThinkPHP {@code error($msg)}：参数错误、未开启、获取单据失败、已支付完成。 */
    record Message(String msg) implements PaymentStep {
    }

    /** 判断通过但没有对应的支付类（{@code pay} 段里的标量项）：旧版 gopay 什么都不输出（200、空正文）。 */
    record Blank() implements PaymentStep {
    }

    /** 码支付、Zhapay：浏览器跳到网关地址（W3）。 */
    record Redirect(String url) implements PaymentStep {
    }

    /** 支付宝：POST 到网关的表单（W3），字段顺序同旧版。 */
    record AutoPost(String action, List<Field> fields) implements PaymentStep {

        public AutoPost {
            fields = List.copyOf(fields);
        }
    }

    /** 微信 NATIVE：订单与统一下单返回的 {@code code_url}（失败时为空）。 */
    record Weixin(FinanceRecords.Order order, Optional<String> codeUrl) implements PaymentStep {
    }

    /** 表单字段。 */
    record Field(String name, String value) {
    }
}
