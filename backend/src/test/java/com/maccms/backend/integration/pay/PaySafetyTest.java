package com.maccms.backend.integration.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayRequest;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * 支付的边界与安全（B24，33 文档第 3、4、10 节）：回调 XML 拒绝 DOCTYPE/实体与属性；网关地址参数的校验；PHP 7 的浮点转字符串；
 * {@code loopback-only} 下微信统一下单在发出前失败、按"获取二维码失败"处理，日志不含密钥、签名与商户号。
 */
@ExtendWith(OutputCaptureExtension.class)
class PaySafetyTest {

    private static final JsonNode GOLDEN = PayGolden.load(PayGolden.PHP73);

    @AfterEach
    void guard() {
        assertThat(TestOutboundGuard.drainViolations()).isEmpty();
    }

    private static PaymentGateways gateways(String weixin) {
        ThirdPartyConfig config = PayGolden.config(GOLDEN, null);
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(1), Duration.ofSeconds(2), 1 << 16,
                config.secretValues());
        return PayConfiguration.create(client, config, null, null, null, weixin);
    }

    private static String weixinNotify(String xml) {
        List<String> credited = new ArrayList<>();
        String out = gateways(null).notify("weixin", new PayNotify(Map.of(), Map.of(), xml.getBytes(StandardCharsets.UTF_8)),
                (code, type) -> {
                    credited.add(code);
                    return 1;
                });
        assertThat(credited).as("不到账").isEmpty();
        return out;
    }

    @Test
    void weixinXmlRejectsDoctypeEntitiesAndAttributes() {
        assertThat(weixinNotify("<?xml version=\"1.0\"?><!DOCTYPE xml [<!ENTITY x SYSTEM \"file:///C:/Windows/win.ini\">]>"
                + "<xml><return_code>&x;</return_code></xml>")).isEqualTo(WeixinPay.FAIL);
        assertThat(weixinNotify("<xml><return_code a=\"1\">SUCCESS</return_code></xml>")).isEqualTo(WeixinPay.FAIL);
        assertThat(WeixinXml.parse("<xml>text</xml>".getBytes(StandardCharsets.UTF_8))).as("根元素只有文本").isEmpty();
    }

    @Test
    void weixinXmlFollowsSimpleXmlShapes() {
        Map<String, WeixinXml.Value> data = WeixinXml.parse(("<xml><a><![CDATA[x&y]]></a><b></b><c><d>1</d><d>2</d></c>"
                + "<e>0</e></xml>").getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(WeixinXml.text(data, "a")).isEqualTo("x&y");
        assertThat(data.get("b")).isEqualTo(new WeixinXml.Value.Group(Map.of()));
        // array_filter 去掉空元素与 "0"；下一级按 http_build_query 写成 c[0]、c[1]（再 urldecode）
        assertThat(WeixinXml.sign(data, "k")).isEqualTo(PhpPay.md5("a=x&y&c[d][0]=1&c[d][1]=2&key=k").toUpperCase());
    }

    @Test
    void phpFloatTextUsesPrecision14() {
        assertThat(PhpPay.floatText(Double.parseDouble("19.99") * 100)).isEqualTo("1999");
        assertThat(PhpPay.floatText(Double.parseDouble("0.01") * 100)).isEqualTo("1");
        assertThat(PhpPay.floatText(Double.parseDouble("20.00") * 100)).isEqualTo("2000");
        assertThat(PhpPay.floatText(Double.parseDouble("70000.00") * 100)).isEqualTo("7000000");
        assertThat(PhpPay.floatText(Double.parseDouble("0.105") * 100)).isEqualTo("10.5");
        assertThat(PhpPay.floatText(0)).isEqualTo("0");
        assertThat(PhpPay.isNumeric("2000")).isTrue();
        assertThat(PhpPay.isNumeric(" 1e3")).isTrue();
        assertThat(PhpPay.isNumeric("1e3 ")).as("PHP 7 不允许尾随空白").isFalse();
        assertThat(PhpPay.isNumeric("127.0.0.1")).isFalse();
        assertThat(PhpPay.urlencode(PhpPay.bytes("a b&c~é"))).isEqualTo("a+b%26c%7E%C3%A9");
    }

    @Test
    void gatewayParametersAreValidated() {
        assertThat(PayConfiguration.check(null, PayConfiguration.DEFAULT_ALIPAY, "alipay-gateway", true))
                .isEqualTo("https://mapi.alipay.com/gateway.do?_input_charset=utf-8");
        assertThat(PayConfiguration.check(" http://127.0.0.1:9/x ", PayConfiguration.DEFAULT_CODEPAY, "codepay-gateway",
                false)).isEqualTo("http://127.0.0.1:9/x");
        for (String bad : List.of("ftp://x.test/", "https://u:p@x.test/", "https://x.test/?a=1", "https://x.test/#f",
                "https://x.test/'", "/relative")) {
            assertThatThrownBy(() -> PayConfiguration.check(bad, PayConfiguration.DEFAULT_CODEPAY, "codepay-gateway", false))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("codepay-gateway")
                    .hasMessageNotContaining(bad);
        }
    }

    @Test
    void weixinOrderUnderLoopbackOnlyFailsBeforeConnectingAndLeaksNothing(CapturedOutput output) {
        PayStart start = gateways(null).start("weixin", new PayOrder("PAY20261029120000123456", "20.00"),
                new PayRequest(null, "127.0.0.1"), PayGolden::notifyUrl);
        assertThat(((PayStart.Weixin) start).codeUrl()).isEmpty();
        assertThat(output.getAll()).contains("pay.weixin.order failed")
                .doesNotContain("test-only-weixin-key", "1900000109", "PAY20261029120000123456", "<xml>");
    }
}
