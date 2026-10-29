package com.maccms.backend.integration.sms;

import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;

/**
 * 腾讯云短信（E06，B25；32 文档第 3.3 节），对应旧 {@code common/extend/sms/Qcloud.php} 的 {@code sendWithParam}：
 * <ul>
 * <li>地址：{@code <接口>?sdkappid=<sms.qcloud.appid>&random=<100000～999999>}（拼接方式与旧版相同，不编码）；接口默认
 * {@code https://yun.tim.qq.com/v5/tlssmssvr/sendsms}，由部署参数注入；</li>
 * <li>请求体：与旧版 {@code json_encode} 逐字节相同的
 * {@code {"tel":{"nationcode":"86","mobile":…},"sig":…,"tpl_id":…,"params":[验证码],"sign":…,"time":…,"extend":"","ext":""}}，
 * {@code sig = sha256("appkey=<appkey>&random=<random>&time=<time>&mobile=<手机号>")}；Content-Type 与旧 curl 相同
 * （{@code application/x-www-form-urlencoded}）；</li>
 * <li>结果（D-B25-3）：HTTP 200 且 {@code result == 0} → 1 "ok"；HTTP 不是 200 → 101 {@code "<状态码> "}（旧 {@code sendCurlPost} 的
 * {@code errmsg}）；其余 → 101 + {@code errmsg}。旧版判定写成 {@code $rsp['ErrorCode'] !== ''}（响应里没有这个键），任何结果都算成功。</li>
 * </ul>
 */
final class QcloudSms {

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final String endpoint;
    private final Clock clock;
    private final RandomGenerator random;

    QcloudSms(OutboundHttpClient client, ThirdPartyConfig config, String endpoint, Clock clock, RandomGenerator random) {
        this.client = client;
        this.config = config;
        this.endpoint = endpoint;
        this.clock = clock;
        this.random = random;
    }

    SendResult submit(String phone, String code, String typeFlag) {
        String appid = config.text("sms", "qcloud", "appid");
        String appkey = config.secret("sms", "qcloud", "appkey").reveal();
        int rnd = 100000 + random.nextInt(900000);
        long time = clock.instant().getEpochSecond();
        String body = body(phone, sig(appkey, rnd, time, phone), config.text("sms", "tpl_code_" + typeFlag), code,
                config.text("sms", "sign"), time);
        URI uri;
        try {
            uri = new URI(endpoint + "?sdkappid=" + appid + "&random=" + rnd);
        } catch (URISyntaxException e) {
            // 旧版 curl 会原样发出；新版地址不合法（appid 含空格等）时按请求失败处理
            return new SendResult(101, "");
        }
        OutboundResponse r;
        try {
            r = client.send(OutboundRequest.post("sms.qcloud", uri, "application/x-www-form-urlencoded",
                    body.getBytes(StandardCharsets.UTF_8)));
        } catch (OutboundException e) {
            return new SendResult(101, "");
        }
        if (r.status() != 200) {
            return new SendResult(101, r.status() + " ");
        }
        JsonNode rsp = LegacySmsJson.decodeObject(r.body());
        JsonNode result = LegacySmsJson.get(rsp, "result");
        if (result != null && result.isNumber() && result.decimalValue().compareTo(BigDecimal.ZERO) == 0) {
            return new SendResult(1, "ok");
        }
        return new SendResult(101, LegacySmsJson.text(LegacySmsJson.get(rsp, "errmsg")));
    }

    /** 旧 {@code calculateSigForTempl}。 */
    static String sig(String appkey, int random, long time, String phone) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] d = sha.digest(("appkey=" + appkey + "&random=" + random + "&time=" + time + "&mobile=" + phone)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", null);
        }
    }

    /** 旧 {@code json_encode($data)}（默认选项：转义 {@code /} 与非 ASCII），字段顺序与旧代码赋值顺序相同。 */
    static String body(String phone, String sig, String tplId, String code, String sign, long time) {
        return "{\"tel\":{\"nationcode\":\"86\",\"mobile\":" + SmsJson.string(phone, false) + "},\"sig\":"
                + SmsJson.string(sig, false) + ",\"tpl_id\":" + SmsJson.string(tplId, false) + ",\"params\":["
                + SmsJson.string(code, false) + "],\"sign\":" + SmsJson.string(sign, false) + ",\"time\":" + time
                + ",\"extend\":\"\",\"ext\":\"\"}";
    }
}
