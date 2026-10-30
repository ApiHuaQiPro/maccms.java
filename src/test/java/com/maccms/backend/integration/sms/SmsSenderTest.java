package com.maccms.backend.integration.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 短信发送（E06，B25；32 文档第 3 节）：{@code mac_send_sms} 的前置检查；阿里云与腾讯云的请求逐项与 PHP 黄金相同（假密钥，签名用 PHP 黄金里
 * 记录的请求做已知答案向量）；结果判定；请求失败、超时、{@code loopback-only} 下的外部地址一律按失败返回而不抛出；号码、验证码、密钥不进日志。
 * 只访问本机模拟服务。
 */
@ExtendWith(OutputCaptureExtension.class)
class SmsSenderTest {

    private static final String ALIYUN_ID = "test-only-aliyun-id";
    private static final String ALIYUN_SECRET = "test-only-aliyun-secret";
    private static final String QCLOUD_ID = "1400000000";
    private static final String QCLOUD_KEY = "test-only-qcloud-key";
    private static final String PHONE = "13800000001";
    private static final String CODE = "093715";
    private static final Instant NOW = Instant.parse("2026-10-29T05:26:18.123456Z");

    private LoopbackMockServer mock;
    private final Deque<MockResponse> aliyunQueue = new ArrayDeque<>();
    private final Deque<MockResponse> qcloudQueue = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
        mock.stub("POST", "/", r -> next(aliyunQueue));
        mock.stub("POST", "/v5/tlssmssvr/sendsms", r -> next(qcloudQueue));
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private static MockResponse next(Deque<MockResponse> q) {
        MockResponse m = q.poll();
        return m == null ? MockResponse.json(599, "unplanned") : m;
    }

    private static ThirdPartyConfig config(String type) {
        Map<Object, PhpValue> sms = new LinkedHashMap<>();
        sms.put("type", new PhpValue.PhpString(type));
        sms.put("sign", new PhpValue.PhpString("测试签名"));
        sms.put("tpl_code_reg", new PhpValue.PhpString("SMS_144850895"));
        sms.put("tpl_code_bind", new PhpValue.PhpString("SMS_144940283"));
        sms.put("tpl_code_findpass", new PhpValue.PhpString("SMS_144851023"));
        sms.put("aliyun", new PhpArray(Map.of("appid", new PhpValue.PhpString(ALIYUN_ID),
                "appkey", new PhpValue.PhpString(ALIYUN_SECRET))));
        sms.put("qcloud", new PhpArray(Map.of("appid", new PhpValue.PhpString(QCLOUD_ID),
                "appkey", new PhpValue.PhpString(QCLOUD_KEY))));
        return ThirdPartyConfig.from(new PhpArray(Map.of("sms", new PhpArray(sms))));
    }

    private SmsSender sender(String type) {
        return sender(type, Duration.ofSeconds(5), OutboundPolicy.Mode.LOOPBACK_ONLY, mock.uri("/").toString(),
                mock.uri("/v5/tlssmssvr/sendsms").toString());
    }

    private SmsSender sender(String type, Duration read, OutboundPolicy.Mode mode, String aliyun, String qcloud) {
        ThirdPartyConfig config = config(type);
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(mode), IntegrationPackages.REGISTERED.keySet(),
                Duration.ofSeconds(2), read, 1 << 16, config.secretValues());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RandomGenerator random = new java.util.Random(42);
        return new LegacySmsSender(config,
                new AliyunSms(client, config, URI.create(SmsConfiguration.checkEndpoint(aliyun, SmsConfiguration.DEFAULT_ALIYUN,
                        "aliyun-endpoint")), clock, random),
                new QcloudSms(client, config, SmsConfiguration.checkEndpoint(qcloud, SmsConfiguration.DEFAULT_QCLOUD,
                        "qcloud-endpoint"), clock, random));
    }

    // ------------------------------------------------------------------ mac_send_sms 的前置检查

    @Test
    void legacyPreconditionsKeepTheirOrderAndCodes() {
        assertThat(sender("").send(PHONE, CODE, "reg")).isEqualTo(new SendResult(9005, "未配置短信发送服务"));
        assertThat(sender("0").send(PHONE, CODE, "reg")).as("empty('0')").isEqualTo(new SendResult(9005, "未配置短信发送服务"));
        for (String bad : List.of("12345678901", "1380000000", "138000000011", " 13800000001", "1380000000a", "")) {
            assertThat(sender("Aliyun").send(bad, CODE, "reg")).as(bad).isEqualTo(new SendResult(999, "手机号格式不正确"));
        }
        assertThat(sender("Aliyun").send(PHONE, "", "reg")).isEqualTo(new SendResult(998, "标题不能为空"));
        assertThat(sender("Aliyun").send(PHONE, CODE, "")).isEqualTo(new SendResult(997, "模板编号不能为空"));
        assertThat(sender("foo").send(PHONE, CODE, "reg")).isEqualTo(new SendResult(991, "未找到该短信发送方式"));
        assertThat(sender("ALIYUN").send(PHONE, CODE, "reg")).as("ucfirst 不改其余字母，Linux 下找不到 ALIYUN.php")
                .isEqualTo(new SendResult(991, "未找到该短信发送方式"));
        assertThat(mock.requests()).isEmpty();
        // PCRE 的 $ 也匹配最后一个换行之前（旧版照发）
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":\"OK\"}"));
        assertThat(sender("aliyun").send(PHONE + "\n", CODE, "reg")).isEqualTo(new SendResult(1, "ok"));
    }

    // ------------------------------------------------------------------ 阿里云

    @Test
    void aliyunSendsTheLegacyRequest(CapturedOutput output) {
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"x\",\"BizId\":\"y\"}"));
        assertThat(sender("Aliyun").send(PHONE, CODE, "bind")).isEqualTo(new SendResult(1, "ok"));
        RecordedRequest q = mock.requests().get(0);
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.path()).isEqualTo("/");
        assertThat(q.query()).isNullOrEmpty();
        assertThat(q.header("content-type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(q.header("x-sdk-client")).as("旧版这个头的写法 curl 不认，没有发出").isNull();
        Map<String, String> p = form(q.bodyText());
        assertThat(p.keySet()).containsExactly("Signature", "AccessKeyId", "Action", "Format", "PhoneNumbers", "RegionId",
                "SignName", "SignatureMethod", "SignatureNonce", "SignatureVersion", "TemplateCode", "TemplateParam",
                "Timestamp", "Version");
        assertThat(p).containsEntry("AccessKeyId", ALIYUN_ID).containsEntry("Action", "SendSms").containsEntry("Format", "JSON")
                .containsEntry("PhoneNumbers", PHONE).containsEntry("RegionId", "cn-hangzhou").containsEntry("SignName", "测试签名")
                .containsEntry("SignatureMethod", "HMAC-SHA1").containsEntry("SignatureVersion", "1.0")
                .containsEntry("TemplateCode", "SMS_144940283").containsEntry("TemplateParam", "{\"code\":\"" + CODE + "\"}")
                .containsEntry("Timestamp", "2026-10-29T05:26:18Z").containsEntry("Version", "2017-05-25");
        assertThat(p.get("SignatureNonce")).matches("[0-9]{1,5}[0-9a-f]{13}\\.[0-9]{8}");
        assertThat(q.bodyText()).as("签名可用假密钥复算").isEqualTo(resign(p, ALIYUN_SECRET));
        assertThat(output.getAll()).doesNotContain(PHONE, CODE, ALIYUN_SECRET, p.get("Signature"));
    }

    /** 已知答案向量：PHP 黄金里旧代码发出的请求体，用同样的参数与假密钥重新签名，逐字节相同。 */
    @Test
    void aliyunSignatureMatchesThePhpGoldenVectors() throws Exception {
        int vectors = 0;
        for (String file : List.of("php73-golden.json", "php80-golden.json")) {
            for (JsonNode c : golden(file).get("cases")) {
                for (JsonNode r : c.get("requests")) {
                    if (!r.get("host").asString().equals("dysmsapi.aliyuncs.com")) {
                        continue;
                    }
                    String body = r.get("body").asString();
                    Map<String, String> p = form(body);
                    assertThat(body).as(file + " " + c.get("label").asString()).isEqualTo(resign(p, ALIYUN_SECRET));
                    vectors++;
                }
            }
        }
        assertThat(vectors).isGreaterThanOrEqualTo(10);
    }

    @Test
    void aliyunResultsFollowTheLegacyChecks() {
        SmsSender s = sender("Aliyun");
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\",\"Message\":\"触发分钟级流控Permits:1\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, "触发分钟级流控Permits:1"));
        aliyunQueue.add(MockResponse.json(500, "{\"Code\":\"InternalError\",\"Message\":\"内部错误\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).as("旧 curl 不看状态码").isEqualTo(new SendResult(101, "内部错误"));
        aliyunQueue.add(MockResponse.text(200, "<html>bad gateway</html>"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, ""));
        aliyunQueue.add(MockResponse.json(200, "{\"result\":\"1\",\"Message\":\"x\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).as("result == 1 也算成功").isEqualTo(new SendResult(1, "ok"));
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":0,\"Message\":\"x\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).as("PHP 7：0 == 'OK'").isEqualTo(new SendResult(1, "ok"));
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":\"ok\",\"Message\":[1]}"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, "Array"));
        aliyunQueue.add(MockResponse.json(200, "{\"Code\":\"OK\"}").withDelay(Duration.ofSeconds(3)));
        assertThat(sender("Aliyun", Duration.ofSeconds(1), OutboundPolicy.Mode.LOOPBACK_ONLY, mock.uri("/").toString(),
                SmsConfiguration.DEFAULT_QCLOUD).send(PHONE, CODE, "reg")).as("超时").isEqualTo(new SendResult(101, ""));
    }

    @Test
    void failuresBeforeConnectingAreReportedNotThrown() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThat(sender("Aliyun", Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY,
                "http://127.0.0.1:" + closedPort + "/", SmsConfiguration.DEFAULT_QCLOUD).send(PHONE, CODE, "reg"))
                .as("连接被拒").isEqualTo(new SendResult(101, ""));
        // 紧急停止出站（loopback-only）时，默认的外部地址被拒绝，不连接
        assertThat(sender("Aliyun", Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY, SmsConfiguration.DEFAULT_ALIYUN,
                SmsConfiguration.DEFAULT_QCLOUD).send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, ""));
        assertThat(sender("Qcloud", Duration.ofSeconds(2), OutboundPolicy.Mode.LOOPBACK_ONLY, SmsConfiguration.DEFAULT_ALIYUN,
                SmsConfiguration.DEFAULT_QCLOUD).send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, ""));
        assertThat(mock.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ 腾讯云

    @Test
    void qcloudSendsTheLegacyRequest(CapturedOutput output) {
        qcloudQueue.add(MockResponse.json(200, "{\"result\":0,\"errmsg\":\"OK\",\"ext\":\"\",\"sid\":\"s\",\"fee\":1}"));
        assertThat(sender("Qcloud").send(PHONE, CODE, "findpass")).isEqualTo(new SendResult(1, "ok"));
        RecordedRequest q = mock.requests().get(0);
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.path()).isEqualTo("/v5/tlssmssvr/sendsms");
        assertThat(q.query()).matches("sdkappid=" + QCLOUD_ID + "&random=[1-9][0-9]{5}");
        assertThat(q.header("content-type")).as("与旧 curl 相同").isEqualTo("application/x-www-form-urlencoded");
        int random = Integer.parseInt(q.query().substring(q.query().indexOf("random=") + 7));
        long time = NOW.getEpochSecond();
        String sig = QcloudSms.sig(QCLOUD_KEY, random, time, PHONE);
        assertThat(q.bodyText()).isEqualTo("{\"tel\":{\"nationcode\":\"86\",\"mobile\":\"" + PHONE + "\"},\"sig\":\"" + sig
                + "\",\"tpl_id\":\"SMS_144851023\",\"params\":[\"" + CODE + "\"],\"sign\":\"\\u6d4b\\u8bd5\\u7b7e\\u540d\",\"time\":"
                + time + ",\"extend\":\"\",\"ext\":\"\"}");
        assertThat(output.getAll()).doesNotContain(PHONE, CODE, QCLOUD_KEY, sig);
    }

    /** 已知答案向量：PHP 黄金里的腾讯云请求体由 random、time、号码、验证码复算，逐字节相同（含 sig）。 */
    @Test
    void qcloudBodyMatchesThePhpGoldenVectors() throws Exception {
        int vectors = 0;
        for (String file : List.of("php73-golden.json", "php80-golden.json")) {
            for (JsonNode c : golden(file).get("cases")) {
                for (JsonNode r : c.get("requests")) {
                    if (!r.get("host").asString().equals("yun.tim.qq.com")) {
                        continue;
                    }
                    String query = r.get("query").asString();
                    int random = Integer.parseInt(query.substring(query.indexOf("random=") + 7));
                    JsonNode body = JsonMapper.builder().build().readTree(r.get("body").asString());
                    String phone = body.get("tel").get("mobile").asString();
                    long time = body.get("time").asLong();
                    String sig = QcloudSms.sig(QCLOUD_KEY, random, time, phone);
                    assertThat(r.get("body").asString()).as(file + " " + c.get("label").asString())
                            .isEqualTo(QcloudSms.body(phone, sig, body.get("tpl_id").asString(),
                                    body.get("params").get(0).asString(), body.get("sign").asString(), time));
                    assertThat(query).isEqualTo("sdkappid=" + QCLOUD_ID + "&random=" + random);
                    vectors++;
                }
            }
        }
        assertThat(vectors).isGreaterThanOrEqualTo(5);
    }

    /** D-B25-3：旧版任何结果都算成功；新版只有 HTTP 200 且 result 为 0 才算成功。 */
    @Test
    void qcloudFailuresAreReported() {
        SmsSender s = sender("Qcloud");
        qcloudQueue.add(MockResponse.json(200, "{\"result\":1014,\"errmsg\":\"package format error\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, "package format error"));
        qcloudQueue.add(MockResponse.json(500, "{\"result\":0}"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, "500 "));
        qcloudQueue.add(MockResponse.json(200, "{\"result\":\"0\",\"errmsg\":\"x\"}"));
        assertThat(s.send(PHONE, CODE, "reg")).as("result 必须是数字 0").isEqualTo(new SendResult(101, "x"));
        qcloudQueue.add(MockResponse.text(200, "not json"));
        assertThat(s.send(PHONE, CODE, "reg")).isEqualTo(new SendResult(101, ""));
    }

    @Test
    void endpointsMustBePlainHttpUrls() {
        for (String bad : List.of("ftp://x/", "http://u:p@x/", "http://x/?a=1", "http://x/#f", "x")) {
            assertThatThrownBy(() -> SmsConfiguration.checkEndpoint(bad, SmsConfiguration.DEFAULT_ALIYUN, "aliyun-endpoint"))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("u:p");
        }
        assertThat(SmsConfiguration.checkEndpoint(" ", SmsConfiguration.DEFAULT_ALIYUN, "aliyun-endpoint"))
                .isEqualTo("http://dysmsapi.aliyuncs.com/");
    }

    // ------------------------------------------------------------------ 辅助

    private static JsonNode golden(String file) throws Exception {
        try (InputStream in = SmsSenderTest.class.getResourceAsStream("/integration/b25/" + file)) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    /** 请求体按 {@code &} 与 {@code =} 拆开并解码，保持顺序。 */
    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 去掉 Signature，按旧版规则重新签名得到的请求体。 */
    private static String resign(Map<String, String> params, String secret) {
        Map<String, String> sorted = new TreeMap<>(AliyunSms::byteOrder);
        params.forEach((k, v) -> {
            if (!k.equals("Signature")) {
                sorted.put(k, v);
            }
        });
        return AliyunSms.signedBody(sorted, secret);
    }
}
