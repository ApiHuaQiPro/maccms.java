package com.maccms.backend.integration.pay;

import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在线支付的装配（B24，33 文档第 4 节）。四个网关地址是部署参数，默认与旧版写死的地址相同：
 * <ul>
 * <li>{@code maccms.integration.pay.alipay-gateway}（{@code MACCMS_INTEGRATION_PAY_ALIPAY_GATEWAY}），默认
 * {@code https://mapi.alipay.com/gateway.do?_input_charset=utf-8}（表单的 action，可以带查询串）；</li>
 * <li>{@code maccms.integration.pay.codepay-gateway}，默认 {@code https://api.xiuxiu888.com/creat_order/}；</li>
 * <li>{@code maccms.integration.pay.zhapay-gateway}，默认 {@code https://www.zhapay.com/mapay.html}；</li>
 * <li>{@code maccms.integration.pay.weixin-endpoint}，默认 {@code https://api.mch.weixin.qq.com/pay/unifiedorder}（服务端请求）。</li>
 * </ul>
 * 必须是不带凭证与片段的 http/https 地址（除支付宝外也不能带查询串），否则启动失败。商户号、appid、密钥照旧取自旧配置 {@code pay.*}
 * （后台"支付配置"页保存，B15），重启生效（D-B23-5）。
 */
@Configuration(proxyBeanMethods = false)
public class PayConfiguration {

    static final String DEFAULT_ALIPAY = "https://mapi.alipay.com/gateway.do?_input_charset=utf-8";
    static final String DEFAULT_CODEPAY = "https://api.xiuxiu888.com/creat_order/";
    static final String DEFAULT_ZHAPAY = "https://www.zhapay.com/mapay.html";
    static final String DEFAULT_WEIXIN = "https://api.mch.weixin.qq.com/pay/unifiedorder";

    @Bean
    PaymentGateways paymentGateways(OutboundHttpClient client, ThirdPartyConfig config,
                                    @Value("${maccms.integration.pay.alipay-gateway:" + DEFAULT_ALIPAY + "}") String alipay,
                                    @Value("${maccms.integration.pay.codepay-gateway:" + DEFAULT_CODEPAY + "}") String codepay,
                                    @Value("${maccms.integration.pay.zhapay-gateway:" + DEFAULT_ZHAPAY + "}") String zhapay,
                                    @Value("${maccms.integration.pay.weixin-endpoint:" + DEFAULT_WEIXIN + "}") String weixin) {
        return create(client, config, alipay, codepay, zhapay, weixin);
    }

    /** 按给定的配置与地址创建（装配与测试共用；地址不合法时抛 {@link IllegalStateException}）。 */
    public static PaymentGateways create(OutboundHttpClient client, ThirdPartyConfig config, String alipay, String codepay,
                                         String zhapay, String weixin) {
        return new LegacyPaymentGateways(config,
                new AlipayPay(config, check(alipay, DEFAULT_ALIPAY, "alipay-gateway", true)),
                new FormGatewayPay(FormGatewayPay.CODEPAY, config, check(codepay, DEFAULT_CODEPAY, "codepay-gateway", false)),
                new WeixinPay(client, config, URI.create(check(weixin, DEFAULT_WEIXIN, "weixin-endpoint", false)),
                        new SecureRandom()),
                new FormGatewayPay(FormGatewayPay.ZHAPAY, config, check(zhapay, DEFAULT_ZHAPAY, "zhapay-gateway", false)));
    }

    static String check(String endpoint, String fallback, String name, boolean queryAllowed) {
        String e = endpoint == null || endpoint.isBlank() ? fallback : endpoint.strip();
        URI u;
        try {
            u = new URI(e);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("maccms.integration.pay." + name + " 不是合法地址", null);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null
                || (!queryAllowed && u.getRawQuery() != null) || u.getRawFragment() != null || u.getRawUserInfo() != null
                || e.contains("'") || e.contains("\"") || e.contains("<") || e.contains(">")) {
            throw new IllegalStateException("maccms.integration.pay." + name + " 必须是不带凭证"
                    + (queryAllowed ? "" : "、查询串") + "与片段的 http/https 地址");
        }
        return e;
    }
}
