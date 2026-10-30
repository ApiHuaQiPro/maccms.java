package com.maccms.backend.integration.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.OrderCredit;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayRequest;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 四种支付方式对照 PHP 黄金（B24，33 文档第 3、9 节；假密钥，只访问本机模拟服务）：
 * <ul>
 * <li>下单：支付宝表单（action 与隐藏字段逐项）、码支付与 Zhapay 的网关地址（逐字节）与 PHP 7.3 相同；PHP 8.0 下旧代码能运行的用例（码支付、
 * Zhapay，除"通道参数不是数字"一例外）两版相同，同样逐项相等；</li>
 * <li>微信统一下单：请求体去掉 nonce 与签名后逐字节相同、签名可用假密钥复算、nonce 格式、Content-Type 与 Referer 头；各种返回（成功、
 * return_code/result_code 失败、HTTP 500、拒绝连接）下是否得到 code_url 与旧版相同；</li>
 * <li>回调：每个用例的回复正文与 PHP 7.3 相同，到账调用（单号与方式）与旧版改动的订单一致；签名错误、篡改、缺签名、流水号为假值、
 * 大小写不同的签名都不到账。</li>
 * </ul>
 * 订单、积分与流水的写库效果由 {@code PaymentGoldenMySqlIT} 在一次性库上对照。
 */
class PaymentGoldenTest {

    /** 新版与 PHP 7.3 有意不同的回调用例 → 新版的回复（33 文档第 10 节）。 */
    private static final Map<String, String> JAVA_NOTIFY = Map.of(
            // D-B24-4：旧版写流水时 PDOException 500（订单与积分已改）；新版到账、不写流水，回复成功
            "notify.alipay.plog-overflow", "success2");

    private LoopbackMockServer mock;

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private PaymentGateways gateways(ThirdPartyConfig config, String weixinEndpoint) {
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(5), 1 << 16,
                config.secretValues());
        return PayConfiguration.create(client, config, null, null, null, weixinEndpoint);
    }

    @Test
    void submitMatchesPhp73() {
        JsonNode golden = PayGolden.load(PayGolden.PHP73);
        int checked = submit(golden, Set.of());
        assertThat(checked).isEqualTo(12);
    }

    @Test
    void submitMatchesPhp80WhereLegacyRuns() {
        // PHP 8.0：支付宝调用已删除的 each()（500）；码支付 paytype 不是数字时整数 0 不再等于空串（多出 type=0），以 7.3 为准
        JsonNode golden = PayGolden.load(PayGolden.PHP80);
        int checked = submit(golden, Set.of("gopay.codepay.type-text"));
        assertThat(checked).isEqualTo(7);
    }

    private int submit(JsonNode golden, Set<String> skip) {
        int checked = 0;
        for (JsonNode c : golden.get("cases")) {
            String label = c.get("label").asString();
            JsonNode parsed = c.get("outputParsed");
            if (!c.get("op").asString().equals("gopay") || parsed == null || parsed.isNull() || skip.contains(label)) {
                continue;
            }
            JsonNode q = c.get("query");
            Map<String, String> query = query(q.asString());
            String payment = query.get("payment").toLowerCase(Locale.ROOT);
            JsonNode o = PayGolden.order(golden, Long.parseLong(query.get("order_id")));
            PayStart start = gateways(PayGolden.config(golden, c.get("configOverrides")), mock.uri("/u").toString())
                    .start(payment, new PayOrder(o.get("order_code").asString(), o.get("order_price").asString()),
                            new PayRequest(query.get("paytype"), "127.0.0.1"), PayGolden::notifyUrl);
            if (parsed.get("kind").asString().equals("alipayForm")) {
                PayStart.AutoPost post = (PayStart.AutoPost) start;
                assertThat(post.action()).as(label).isEqualTo(parsed.get("action").asString());
                List<List<String>> fields = new ArrayList<>();
                post.fields().forEach(f -> fields.add(List.of(f.name(), f.value())));
                List<List<String>> expected = new ArrayList<>();
                parsed.get("fields").forEach(f -> expected.add(List.of(f.get(0).asString(), f.get(1).asString())));
                assertThat(fields).as(label).isEqualTo(expected);
            } else {
                assertThat(((PayStart.Redirect) start).url()).as(label).isEqualTo(parsed.get("url").asString());
            }
            checked++;
        }
        return checked;
    }

    @Test
    void weixinUnifiedOrderMatchesPhp73() throws IOException {
        JsonNode golden = PayGolden.load(PayGolden.PHP73);
        int checked = 0;
        for (JsonNode c : golden.get("cases")) {
            String label = c.get("label").asString();
            if (!label.startsWith("gopay.weixin.")) {
                continue;
            }
            mock.close();
            mock = LoopbackMockServer.start();
            JsonNode plan = c.get("mockPlan").get("api.mch.weixin.qq.com");
            if (plan != null) {
                mock.stub("POST", "/pay/unifiedorder", MockResponse.of(plan.get("status").asInt(), "text/plain",
                        plan.get("body").asString()));
            }
            String endpoint = c.get("proxy").asString().equals("refuse") ? "http://127.0.0.1:" + deadPort()
                    + "/pay/unifiedorder" : mock.uri("/pay/unifiedorder").toString();
            Map<String, String> query = query(c.get("query").asString());
            JsonNode o = PayGolden.order(golden, Long.parseLong(query.get("order_id")));
            ThirdPartyConfig config = PayGolden.config(golden, c.get("configOverrides"));
            PayStart.Weixin w = (PayStart.Weixin) gateways(config, endpoint).start("weixin",
                    new PayOrder(o.get("order_code").asString(), o.get("order_price").asString()),
                    new PayRequest(null, "127.0.0.1"), PayGolden::notifyUrl);
            JsonNode assigned = c.get("assignedPayment");
            if (assigned.isObject()) {
                assertThat(w.codeUrl()).as(label).contains(assigned.get("code_url").asString());
                assertThat(w.totalFee()).as(label).isEqualTo(assigned.get("total_fee").asString());
                assertThat(w.outTradeNo()).as(label).isEqualTo(assigned.get("out_trade_no").asString());
            } else {
                assertThat(w.codeUrl()).as(label).isEmpty();
            }
            List<RecordedRequest> requests = c.get("proxy").asString().equals("refuse") ? List.of() : mock.requests();
            assertThat(requests).as(label).hasSize(c.get("requests").size());
            if (!requests.isEmpty()) {
                RecordedRequest r = requests.get(0);
                JsonNode expected = c.get("requests").get(0);
                String body = new String(r.body(), StandardCharsets.UTF_8);
                assertThat(body.replaceAll("<nonce_str><!\\[CDATA\\[[^\\]]*\\]\\]></nonce_str>", "<nonce_str><nonce></nonce_str>")
                        .replaceAll("<sign><!\\[CDATA\\[[0-9A-F]{32}\\]\\]></sign>", "<sign><sign></sign>"))
                        .as(label).isEqualTo(expected.get("bodyElided").asString());
                assertThat(expected.get("signatureValid").asBoolean()).isTrue();
                assertThat(signatureValid(r.body(), config)).as(label + " 签名可复算").isTrue();
                assertThat(body).as(label).containsPattern("<nonce_str><!\\[CDATA\\[[1-9A-Z][0-9A-Z]{31}\\]\\]></nonce_str>");
                assertThat(r.header("content-type")).isEqualTo("application/x-www-form-urlencoded");
                assertThat(r.header("referer")).isEqualTo(endpoint);
                assertThat(r.header("user-agent")).isEqualTo(header(expected, "user-agent"));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(8);
    }

    private static boolean signatureValid(byte[] body, ThirdPartyConfig config) {
        Map<String, WeixinXml.Value> data = new java.util.LinkedHashMap<>(WeixinXml.parse(body).orElseThrow());
        String sign = WeixinXml.text(data, "sign");
        data.remove("sign");
        return WeixinXml.sign(data, PhpPay.trim(config.secret("pay", "weixin", "appkey").reveal())).equals(sign);
    }

    @Test
    void notifyMatchesPhp73() {
        JsonNode golden = PayGolden.load(PayGolden.PHP73);
        int checked = 0;
        for (JsonNode c : golden.get("cases")) {
            String label = c.get("label").asString();
            if (!c.get("op").asString().equals("notify") || !c.get("method").asString().equals("POST")) {
                continue;
            }
            ThirdPartyConfig config = PayGolden.config(golden, c.get("configOverrides"));
            PaymentGateways g = gateways(config, mock.uri("/u").toString());
            String payType = c.get("payType").asString();
            List<String> credited = new ArrayList<>();
            OrderCredit credit = (code, type) -> {
                credited.add(code + " " + type);
                return legacyCredit(golden, code);
            };
            String out = switch (g.availability(payType)) {
                case NOT_OPEN -> "该支付选项未开启！";
                case NO_METHOD -> "未找到支付选项！";
                case AVAILABLE -> g.notify(payType, new PayNotify(PayGolden.input(c), PayGolden.post(c), PayGolden.raw(c)),
                        credit);
            };
            String expected = JAVA_NOTIFY.getOrDefault(label, c.get("output").asString());
            assertThat(out).as(label).isEqualTo(expected);
            // 旧版改动了的订单 = 新版的到账调用
            List<String> changed = new ArrayList<>();
            for (JsonNode o : c.get("after").get("orders")) {
                JsonNode seed = PayGolden.order(golden, o.get("order_id").asLong());
                if (seed.get("order_status").asInt() == 0 && o.get("order_status").asInt() == 1) {
                    changed.add(o.get("order_code").asString() + " " + o.get("order_pay_type").asString());
                }
            }
            List<String> successful = credited.stream().filter(s -> changed.stream().anyMatch(s::equals)).toList();
            assertThat(successful).as(label + " 到账").isEqualTo(changed);
            if (changed.isEmpty() && !credited.isEmpty()) {
                // 验签通过但旧版没有改订单：已支付、订单或会员不存在
                assertThat(credited).as(label).allSatisfy(s -> assertThat(legacyCredit(golden, s.split(" ")[0]))
                        .satisfiesAnyOf(x -> assertThat(x).isEqualTo(1002), x -> assertThat(isPaid(golden, s)).isTrue()));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(50);
    }

    @Test
    void notifyMatchesPhp80ForCodepayZhapayWeixin() {
        // PHP 8.0 下支付宝回调（each()）与微信的非 XML 正文（array_filter(false)）是 500；其余与 7.3 相同
        JsonNode golden = PayGolden.load(PayGolden.PHP80);
        int checked = 0;
        for (JsonNode c : golden.get("cases")) {
            if (!c.get("op").asString().equals("notify") || !c.get("method").asString().equals("POST")
                    || c.get("output") == null || c.get("output").asString().isEmpty()) {
                continue;
            }
            String payType = c.get("payType").asString();
            ThirdPartyConfig config = PayGolden.config(golden, c.get("configOverrides"));
            PaymentGateways g = gateways(config, mock.uri("/u").toString());
            if (g.availability(payType) != PaymentGateways.Availability.AVAILABLE) {
                continue;
            }
            String out = g.notify(payType, new PayNotify(PayGolden.input(c), PayGolden.post(c), PayGolden.raw(c)),
                    (code, type) -> legacyCredit(golden, code));
            assertThat(out).as(c.get("label").asString()).isEqualTo(c.get("output").asString());
            checked++;
        }
        assertThat(checked).isEqualTo(27);
    }

    @Test
    void availabilityFollowsLegacyAppidCheck() {
        JsonNode golden = PayGolden.load(PayGolden.PHP73);
        PaymentGateways g = gateways(PayGolden.config(golden, null), mock.uri("/u").toString());
        for (String m : PaymentGateways.METHODS) {
            assertThat(g.availability(m)).as(m).isEqualTo(PaymentGateways.Availability.AVAILABLE);
        }
        assertThat(g.availability("Alipay")).isEqualTo(PaymentGateways.Availability.NOT_OPEN);
        assertThat(g.availability("foo")).isEqualTo(PaymentGateways.Availability.NOT_OPEN);
        assertThat(g.availability("card")).as("数组但没有 appid").isEqualTo(PaymentGateways.Availability.NOT_OPEN);
        assertThat(g.availability("min")).as("标量").isEqualTo(PaymentGateways.Availability.NO_METHOD);
        assertThat(g.availability("scale")).isEqualTo(PaymentGateways.Availability.NO_METHOD);
        assertThat(g.availability("appkey")).as("密钥名").isEqualTo(PaymentGateways.Availability.NOT_OPEN);
        assertThat(g.availability("")).isEqualTo(PaymentGateways.Availability.NOT_OPEN);
        assertThat(g.browserRedirect("weixin")).isFalse();
        assertThat(g.browserRedirect("alipay")).isTrue();
        assertThat(g.browserRedirect("codepay")).isTrue();
        assertThat(g.browserRedirect("zhapay")).isTrue();
        // 仓库配置：四种方式的 appid 都为空
        PaymentGateways repo = gateways(ThirdPartyConfig.from(null), mock.uri("/u").toString());
        assertThat(repo.availability("alipay")).isEqualTo(PaymentGateways.Availability.NOT_OPEN);
    }

    /** 旧 {@code Order::notify} 的返回码（不写库的替身）：订单不存在、会员不存在 1002；已支付或成功 1。 */
    private static int legacyCredit(JsonNode golden, String code) {
        for (JsonNode o : golden.get("seedOrders")) {
            if (o.get("order_code").asString().equalsIgnoreCase(code)) {
                long user = o.get("user_id").asLong();
                for (JsonNode u : golden.get("seedUsers")) {
                    if (u.get("user_id").asLong() == user) {
                        return 1;
                    }
                }
                return o.get("order_status").asInt() == 1 ? 1 : 1002;
            }
        }
        return 1002;
    }

    private static boolean isPaid(JsonNode golden, String credited) {
        String code = credited.split(" ")[0];
        for (JsonNode o : golden.get("seedOrders")) {
            if (o.get("order_code").asString().equals(code)) {
                return o.get("order_status").asInt() == 1;
            }
        }
        return false;
    }

    private static String header(JsonNode request, String name) {
        for (JsonNode h : request.get("headers")) {
            if (h.get(0).asString().equals(name)) {
                return h.get(1).asString();
            }
        }
        return null;
    }

    private static Map<String, String> query(String q) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static int deadPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
