package com.maccms.backend.integration.application;

import java.util.List;
import java.util.Optional;

/** 下单的结果（旧 {@code submit} 的输出）。 */
public sealed interface PayStart {

    /** 码支付、Zhapay：旧 {@code mac_redirect($url)}，浏览器跳到网关地址（W3）。 */
    record Redirect(String url) implements PayStart {
    }

    /**
     * 支付宝：旧版输出的自动提交表单（{@code POST} 到网关，W3）。
     *
     * @param fields 隐藏字段，顺序同旧版（排序后的参数，再加 {@code sign}、{@code sign_type}）
     */
    record AutoPost(String action, List<Field> fields) implements PayStart {

        public AutoPost {
            fields = List.copyOf(fields);
        }
    }

    /**
     * 微信 NATIVE：统一下单成功时的 {@code code_url}（本站生成二维码）；失败（请求失败、{@code return_code}/{@code result_code} 不是
     * {@code SUCCESS}）时为空，同旧版 {@code return false}。
     *
     * @param totalFee 旧版返回的 {@code total_fee}（订单金额原文，单位元）
     */
    record Weixin(Optional<String> codeUrl, String totalFee, String outTradeNo) implements PayStart {
    }

    /** 表单字段。 */
    record Field(String name, String value) {
    }
}
