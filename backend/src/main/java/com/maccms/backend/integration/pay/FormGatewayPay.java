package com.maccms.backend.integration.pay;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.integration.application.OrderCredit;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 码支付与 Zhapay（幻兮支付）：两者的旧代码（{@code common/extend/pay/Codepay.php}、{@code Zhapay.php}）结构相同，只有参数名、网关与
 * 回调细节不同（33 文档第 3.2、3.3 节），PHP 7.3 的结果为准。
 * <ul>
 * <li>下单：参数按键排序，跳过 {@code == ''} 的值（PHP 7 中整数 0 也等于空串，所以 {@code paytype} 不是数字时通道参数被略去），
 * {@code sign = md5("k=v&…" + trim(appkey))}，网关地址 {@code <网关>?<k=urlencode(v)&…>&sign=…}，浏览器跳转（W3）。</li>
 * <li>回调只看 {@code $_POST}：按键排序、跳过空值与 {@code sign}；流水号（码支付 {@code pay_no}、Zhapay {@code transaction_id}）为 PHP
 * 假值或签名不一致 → {@code fail}；否则到账，结果大于 1 → {@code fail2}，否则 {@code success}。码支付验签用 {@code trim(appkey)}，
 * Zhapay 用未经 trim 的 appkey（照旧）。签名比较为严格相等（D-B24-2）。</li>
 * </ul>
 */
final class FormGatewayPay {

    /** 两种方式的差异。 */
    record Spec(String name, String idKey, String typeKey, String priceKey, String codeKey, String actKey,
                String actConfig, boolean returnType, String txKey, boolean trimKeyOnNotify) {
    }

    static final Spec CODEPAY = new Spec("codepay", "id", "type", "price", "pay_id", "ac", "ac", false, "pay_no",
            true);
    static final Spec ZHAPAY = new Spec("zhapay", "mch_uid", "pay_type_id", "total_fee", "out_trade_no",
            "mepay_type", "act", true, "transaction_id", false);

    private final Spec spec;
    private final ThirdPartyConfig config;
    private final String gateway;

    FormGatewayPay(Spec spec, ThirdPartyConfig config, String gateway) {
        this.spec = spec;
        this.config = config;
        this.gateway = gateway;
    }

    PayStart start(PayOrder order, String paytype, String notifyUrl) {
        long type = 1;
        if (paytype != null && !paytype.isEmpty() && !paytype.equals("0")) {
            type = PhpIntval.of(paytype);
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put(spec.idKey(), PhpPay.trim(PhpPay.bytes(config.text("pay", spec.name(), "appid"))));
        // PHP 7：整数 0 == '' 成立，按空值略去
        data.put(spec.typeKey(), type == 0 ? "" : Long.toString(type));
        data.put(spec.priceKey(), PhpPay.bytes(order.price()));
        data.put(spec.codeKey(), PhpPay.bytes(order.code()));
        data.put("notify_url", PhpPay.bytes(notifyUrl));
        data.put("return_url", PhpPay.bytes(notifyUrl));
        data.put("debug", "1");
        // 码支付读的是 pay.codepay.ac（旧配置里只有 act，所以总是空串）；Zhapay 读 pay.zhapay.act
        data.put(spec.actKey(), PhpPay.trim(PhpPay.bytes(config.text("pay", spec.name(), spec.actConfig()))));
        if (spec.returnType()) {
            data.put("return_type", "1");
        }
        data.put("param", "");
        StringBuilder sign = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        PhpPay.ksort(data).forEach((k, v) -> {
            if (v.isEmpty() || k.equals("sign")) {
                return;
            }
            if (!sign.isEmpty()) {
                sign.append('&');
                urls.append('&');
            }
            sign.append(k).append('=').append(v);
            urls.append(k).append('=').append(PhpPay.urlencode(v));
        });
        String key = PhpPay.trim(PhpPay.bytes(config.secret("pay", spec.name(), "appkey").reveal()));
        return new PayStart.Redirect(gateway + "?" + urls + "&sign=" + PhpPay.md5(sign + key));
    }

    String notify(PayNotify notify, OrderCredit credit) {
        Map<String, String> param = new LinkedHashMap<>();
        for (Map.Entry<String, PhpInput.Value> e : notify.post().entrySet()) {
            param.put(PhpPay.bytes(e.getKey()), AlipayPay.value(e.getValue()));
        }
        if (spec == CODEPAY) {
            param.remove("/payment/notify/pay_type/codepay");
            param.remove("paytype");
        }
        StringBuilder sign = new StringBuilder();
        PhpPay.ksort(param).forEach((k, v) -> {
            if (v.isEmpty() || k.equals("sign")) {
                return;
            }
            if (!sign.isEmpty()) {
                sign.append('&');
            }
            sign.append(k).append('=').append(v);
        });
        String key = PhpPay.bytes(config.secret("pay", spec.name(), "appkey").reveal());
        if (spec.trimKeyOnNotify()) {
            key = PhpPay.trim(key);
        }
        String tx = param.get(spec.txKey());
        if (falsy(tx) || !PhpPay.same(PhpPay.md5(sign + key), param.get("sign"))) {
            return "fail";
        }
        String code = param.get(spec.codeKey());
        return credit.credit(code == null ? "" : PhpPay.text(code), spec.name()) > 1 ? "fail2" : "success";
    }

    /** PHP 假值：不存在、{@code ""}、{@code "0"}（数组按 "Array"，为真）。 */
    private static boolean falsy(String v) {
        return v == null || v.isEmpty() || v.equals("0");
    }
}
