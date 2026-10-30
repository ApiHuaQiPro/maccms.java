package com.maccms.backend.integration.pay;

import com.maccms.backend.integration.application.OrderCredit;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayRequest;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.util.function.UnaryOperator;

/** 四种支付方式的分派（旧 {@code 'app\\common\\extend\\pay\\' . ucfirst($payment)}，33 文档第 2 节）。 */
final class LegacyPaymentGateways implements PaymentGateways {

    private final ThirdPartyConfig config;
    private final AlipayPay alipay;
    private final FormGatewayPay codepay;
    private final WeixinPay weixin;
    private final FormGatewayPay zhapay;

    LegacyPaymentGateways(ThirdPartyConfig config, AlipayPay alipay, FormGatewayPay codepay, WeixinPay weixin,
                          FormGatewayPay zhapay) {
        this.config = config;
        this.alipay = alipay;
        this.codepay = codepay;
        this.weixin = weixin;
        this.zhapay = zhapay;
    }

    @Override
    public Availability availability(String payment) {
        if (payment == null || payment.isEmpty() || ThirdPartyConfig.isSecretKey(payment)) {
            // pay 段没有密钥名的直接子项
            return Availability.NOT_OPEN;
        }
        String group = config.text("pay", payment);
        if (!group.equals("Array")) {
            // pay 段里的标量（min、scale）：PHP 7 取 ['appid'] 得到首字符，非空即"判断通过"，随后找不到类
            return group.isEmpty() ? Availability.NOT_OPEN : Availability.NO_METHOD;
        }
        if (config.text("pay", payment, "appid").isEmpty()) {
            return Availability.NOT_OPEN;
        }
        // ucfirst 之后按类名加载：只认小写的四个名字（Linux 下类文件名区分大小写）
        return METHODS.contains(payment) ? Availability.AVAILABLE : Availability.NO_METHOD;
    }

    @Override
    public boolean browserRedirect(String payment) {
        return payment.equals("alipay") || payment.equals("codepay") || payment.equals("zhapay");
    }

    @Override
    public PayStart start(String payment, PayOrder order, PayRequest request, UnaryOperator<String> notifyUrl) {
        String url = notifyUrl.apply(payment);
        return switch (payment) {
            case "alipay" -> alipay.start(order, url);
            case "codepay" -> codepay.start(order, request.paytype(), url);
            case "zhapay" -> zhapay.start(order, request.paytype(), url);
            case "weixin" -> weixin.start(order, request.clientIp(), url);
            default -> throw new IllegalArgumentException("未知支付方式");
        };
    }

    @Override
    public String notify(String payment, PayNotify notify, OrderCredit credit) {
        return switch (payment) {
            case "alipay" -> alipay.notify(notify, credit);
            case "codepay" -> codepay.notify(notify, credit);
            case "zhapay" -> zhapay.notify(notify, credit);
            case "weixin" -> weixin.notify(notify, credit);
            default -> throw new IllegalArgumentException("未知支付方式");
        };
    }
}
