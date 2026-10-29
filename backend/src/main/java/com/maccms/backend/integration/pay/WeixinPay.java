package com.maccms.backend.integration.pay;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.integration.application.OrderCredit;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信支付 NATIVE（旧 {@code common/extend/pay/Weixin.php}，33 文档第 3.4 节），PHP 7.3 的结果为准（PHP 8 下旧 {@code mac_curl_post}
 * 的 {@code curl_setopt(CURLINFO_CONTENT_LENGTH_UPLOAD)} 抛 ValueError，下单是 500）。
 * <ul>
 * <li>下单：服务端经出站客户端 POST 统一下单接口（默认 {@code https://api.mch.weixin.qq.com/pay/unifiedorder}，部署参数），请求体为
 * {@code mac_array2xml} 的结果，头与旧 curl 相同（{@code Content-Type: application/x-www-form-urlencoded}、{@code Referer: <接口>}）；
 * 返回的 {@code return_code}、{@code result_code} 都是 {@code SUCCESS} 时取 {@code code_url}，在本站生成二维码；否则（含请求失败）为失败。</li>
 * <li>回调：解析原始正文，{@code sign} 与复算结果严格相等（旧版就是 {@code ===}）且两个状态都是 {@code SUCCESS} 时到账（结果不影响回复），
 * 回复 {@code SUCCESS/OK}；否则 {@code FAIL/签名失败}。</li>
 * </ul>
 */
final class WeixinPay {

    static final String SUCCESS = "<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>";
    static final String FAIL = "<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[签名失败]]></return_msg></xml>";
    private static final String NONCE = "234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Logger log = LoggerFactory.getLogger(WeixinPay.class);

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final URI endpoint;
    private final RandomGenerator random;

    WeixinPay(OutboundHttpClient client, ThirdPartyConfig config, URI endpoint, RandomGenerator random) {
        this.client = client;
        this.config = config;
        this.endpoint = endpoint;
        this.random = random;
    }

    PayStart start(PayOrder order, String clientIp, String notifyUrl) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("appid", PhpPay.trim(PhpPay.bytes(config.text("pay", "weixin", "appid"))));
        data.put("mch_id", PhpPay.trim(PhpPay.bytes(config.secret("pay", "weixin", "mchid").reveal())));
        data.put("nonce_str", nonce());
        // 旧 gopay 传入的 $this->user 是未定义属性（null），UID 为空（33 文档第 3.5 节）
        data.put("body", PhpPay.bytes("积分充值（UID：）"));
        data.put("fee_type", "CNY");
        data.put("out_trade_no", PhpPay.bytes(order.code()));
        data.put("total_fee", PhpPay.floatText(Double.parseDouble(order.price()) * 100));
        data.put("spbill_create_ip", PhpPay.bytes(clientIp == null ? "" : clientIp));
        data.put("notify_url", PhpPay.bytes(notifyUrl));
        data.put("trade_type", "NATIVE");
        data.put("product_id", "1");
        // array_filter 去掉空串与 "0"（金额 0 的浮点数同样是假值），与 WeixinXml.sign 的规则相同
        data.put("sign", WeixinXml.sign(WeixinXml.flat(data), key()));
        byte[] body = WeixinXml.toXml(data).getBytes(StandardCharsets.ISO_8859_1);
        Optional<String> codeUrl = Optional.empty();
        try {
            OutboundResponse r = client.send(OutboundRequest.post("pay.weixin.order", endpoint,
                    "application/x-www-form-urlencoded", body).withHeader("Referer", endpoint.toString()));
            Optional<Map<String, WeixinXml.Value>> res = WeixinXml.parse(r.body());
            if (res.isPresent() && "SUCCESS".equals(WeixinXml.text(res.get(), "return_code"))
                    && "SUCCESS".equals(WeixinXml.text(res.get(), "result_code"))) {
                codeUrl = Optional.ofNullable(WeixinXml.text(res.get(), "code_url")).map(PhpPay::text);
            }
        } catch (OutboundException e) {
            log.warn("pay.weixin.order failed: {}", SafeExceptionLog.typeChain(e));
        }
        return new PayStart.Weixin(codeUrl, order.price(), order.code());
    }

    String notify(PayNotify notify, OrderCredit credit) {
        Optional<Map<String, WeixinXml.Value>> parsed = WeixinXml.parse(notify.raw());
        if (parsed.isEmpty()) {
            return FAIL;
        }
        Map<String, WeixinXml.Value> data = new LinkedHashMap<>(parsed.get());
        String given = WeixinXml.text(data, "sign");
        data.remove("sign");
        if (given == null || !PhpPay.same(WeixinXml.sign(data, key()), given)
                || !"SUCCESS".equals(WeixinXml.text(data, "return_code"))
                || !"SUCCESS".equals(WeixinXml.text(data, "result_code"))) {
            return FAIL;
        }
        String code = WeixinXml.text(data, "out_trade_no");
        // 旧版不看到账结果，验签通过即回复成功（33 文档 D-B24-a）
        credit.credit(code == null ? "" : PhpPay.text(code), "weixin");
        return SUCCESS;
    }

    private String key() {
        return PhpPay.trim(PhpPay.bytes(config.secret("pay", "weixin", "appkey").reveal()));
    }

    /** 旧 {@code mac_get_rndstr()}：32 位，取自 {@code 234567890A-Z}；首位为 0 时换成 1～9。 */
    private String nonce() {
        StringBuilder s = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            s.append(NONCE.charAt(random.nextInt(NONCE.length())));
        }
        if (s.charAt(0) == '0') {
            s.setCharAt(0, (char) ('1' + random.nextInt(9)));
        }
        return s.toString();
    }
}
