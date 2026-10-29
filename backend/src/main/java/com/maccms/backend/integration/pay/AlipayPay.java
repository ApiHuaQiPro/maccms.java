package com.maccms.backend.integration.pay;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.integration.application.OrderCredit;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝即时到账（旧 {@code common/extend/pay/Alipay.php}，33 文档第 3.1 节），PHP 7.3 的结果为准（PHP 8 下旧代码调用已删除的
 * {@code each()}，下单与回调都是 500）。
 * <ul>
 * <li>下单：参数 {@code service、payment_type、quantity、_input_charset、partner（pay.alipay.appid，trim）、seller_email（account，trim）、
 * out_trade_no、notify_url、return_url、subject、total_fee（%.2f）}；去掉空值与 {@code sign/sign_type}，按键排序，
 * {@code md5("k=v&…" + appkey)}，再加 {@code sign}、{@code sign_type=MD5}，以自动提交表单 POST 到网关（W3）。</li>
 * <li>回调：{@code input()} 去掉 {@code pay_type} 与旧 rewrite 留下的路径键，按同样规则复算签名；签名一致时 {@code trade_status} 为
 * {@code TRADE_SUCCESS} 才到账（结果大于 1 → {@code fail2}，否则 {@code success2}），其他状态 {@code success}；签名不一致 {@code fail}。
 * 签名比较为严格相等（旧版 {@code ==}，D-B24-2）。</li>
 * </ul>
 */
final class AlipayPay {

    private final ThirdPartyConfig config;
    private final String gateway;

    AlipayPay(ThirdPartyConfig config, String gateway) {
        this.config = config;
        this.gateway = gateway;
    }

    PayStart start(PayOrder order, String notifyUrl) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("service", "create_direct_pay_by_user");
        data.put("payment_type", "1");
        data.put("quantity", "1");
        data.put("_input_charset", "utf-8");
        data.put("partner", PhpPay.trim(PhpPay.bytes(config.text("pay", "alipay", "appid"))));
        data.put("seller_email", PhpPay.trim(PhpPay.bytes(config.text("pay", "alipay", "account"))));
        data.put("out_trade_no", PhpPay.bytes(order.code()));
        data.put("notify_url", PhpPay.bytes(notifyUrl));
        data.put("return_url", PhpPay.bytes(notifyUrl));
        // 旧 gopay 传入的 $this->user 是未定义属性（null），UID 为空（33 文档第 3.5 节，按旧版输出）
        data.put("subject", PhpPay.bytes("积分充值（UID：）"));
        data.put("total_fee", new java.math.BigDecimal(order.price()).setScale(2, java.math.RoundingMode.HALF_EVEN)
                .toPlainString());
        Map<String, String> sorted = PhpPay.ksort(filter(data));
        sorted.put("sign", sign(sorted));
        sorted.put("sign_type", "MD5");
        List<PayStart.Field> fields = new ArrayList<>();
        sorted.forEach((k, v) -> fields.add(new PayStart.Field(PhpPay.text(k), PhpPay.text(v))));
        // 表单字段顺序：排序后的参数在前，sign、sign_type 在后（TreeMap 会把它们排进去，这里按旧版的追加顺序重排）
        fields.sort((a, b) -> Integer.compare(rank(a.name()), rank(b.name())));
        return new PayStart.AutoPost(gateway, fields);
    }

    private static int rank(String name) {
        return name.equals("sign") ? 1 : name.equals("sign_type") ? 2 : 0;
    }

    String notify(PayNotify notify, OrderCredit credit) {
        Map<String, String> param = new LinkedHashMap<>();
        for (Map.Entry<String, PhpInput.Value> e : notify.input().entrySet()) {
            param.put(PhpPay.bytes(e.getKey()), value(e.getValue()));
        }
        param.remove("/payment/notify/pay_type/alipay");
        param.remove("pay_type");
        String given = param.get("sign");
        if (!PhpPay.same(sign(PhpPay.ksort(filter(param))), given)) {
            return "fail";
        }
        if (!"TRADE_SUCCESS".equals(param.get("trade_status"))) {
            return "success";
        }
        String code = param.get("out_trade_no");
        return credit.credit(code == null ? "" : PhpPay.text(code), "alipay") > 1 ? "fail2" : "success2";
    }

    /** {@code paraFilter}：去掉 {@code sign}、{@code sign_type} 与空串（数组在旧版里不等于空串，按 "Array" 参与签名）。 */
    private static Map<String, String> filter(Map<String, String> p) {
        Map<String, String> out = new LinkedHashMap<>();
        p.forEach((k, v) -> {
            if (!k.equals("sign") && !k.equals("sign_type") && !v.isEmpty()) {
                out.put(k, v);
            }
        });
        return out;
    }

    private String sign(Map<String, String> sorted) {
        StringBuilder s = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!k.equals("sign") && !k.equals("sign_type")) {
                s.append(k).append('=').append(v).append('&');
            }
        });
        if (!s.isEmpty()) {
            s.setLength(s.length() - 1);
        }
        return PhpPay.md5(s + PhpPay.bytes(config.secret("pay", "alipay", "appkey").reveal()));
    }

    static String value(PhpInput.Value v) {
        return v.array() ? "Array" : new String(v.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
