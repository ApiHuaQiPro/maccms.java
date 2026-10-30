package com.maccms.backend.integration.sms;

import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * 阿里云短信（E06，B25；32 文档第 3.2 节），逐行对应旧 {@code common/extend/sms/Aliyun.php}：
 * <ul>
 * <li>参数：{@code SignatureMethod=HMAC-SHA1}、{@code SignatureNonce}、{@code SignatureVersion=1.0}、{@code AccessKeyId}
 * （{@code sms.aliyun.appid}）、{@code Timestamp}（UTC {@code Y-m-d\TH:i:s\Z}）、{@code Format=JSON}、{@code PhoneNumbers}、
 * {@code SignName}（{@code sms.sign}）、{@code TemplateCode}（{@code sms.tpl_code_<flag>}）、{@code TemplateParam={"code":"<验证码>"}}、
 * {@code RegionId=cn-hangzhou}、{@code Action=SendSms}、{@code Version=2017-05-25}；按键名字节序排序；</li>
 * <li>签名：{@code base64(HMAC-SHA1(appkey + "&", "POST&%2F&" + encode(排序后的查询串)))}，{@code encode} 是 {@code urlencode} 后把
 * {@code +}、{@code *}、{@code %7E} 换成 {@code %20}、{@code %2A}、{@code ~}；</li>
 * <li>请求：POST {@code <接口>}，{@code application/x-www-form-urlencoded}，请求体 {@code Signature=<签名>&<排序后的参数>}；接口默认
 * {@code http://dysmsapi.aliyuncs.com/}（旧版 {@code $security=false}），由部署参数注入；</li>
 * <li>结果：{@code Code == 'OK'} 或 {@code result == 1} → 1 "ok"；否则 101 + {@code Message}（请求失败、不是 JSON 时为空串）。</li>
 * </ul>
 * 密钥只用于计算签名，验证码与号码只出现在请求体里；出站客户端的日志只记主机与状态。
 */
final class AliyunSms {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final URI endpoint;
    private final Clock clock;
    private final RandomGenerator random;

    AliyunSms(OutboundHttpClient client, ThirdPartyConfig config, URI endpoint, Clock clock, RandomGenerator random) {
        this.client = client;
        this.config = config;
        this.endpoint = endpoint;
        this.clock = clock;
        this.random = random;
    }

    SendResult submit(String phone, String code, String typeFlag) {
        Instant now = clock.instant();
        Map<String, String> params = new TreeMap<>(AliyunSms::byteOrder);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", nonce(now));
        params.put("SignatureVersion", "1.0");
        params.put("AccessKeyId", config.text("sms", "aliyun", "appid"));
        params.put("Timestamp", TIMESTAMP.format(now));
        params.put("Format", "JSON");
        params.put("PhoneNumbers", phone);
        params.put("SignName", config.text("sms", "sign"));
        params.put("TemplateCode", config.text("sms", "tpl_code_" + typeFlag));
        params.put("TemplateParam", "{\"code\":" + SmsJson.string(code, true) + "}");
        params.put("RegionId", "cn-hangzhou");
        params.put("Action", "SendSms");
        params.put("Version", "2017-05-25");
        byte[] body = signedBody(params, config.secret("sms", "aliyun", "appkey").reveal())
                .getBytes(StandardCharsets.UTF_8);
        JsonNode rsp;
        try {
            rsp = LegacySmsJson.decodeObject(client.send(OutboundRequest.post("sms.aliyun", endpoint,
                    "application/x-www-form-urlencoded", body)).body());
        } catch (OutboundException e) {
            // 旧版 curl 失败时 trigger_error 后返回 false，json_decode 得到 null
            rsp = null;
        }
        if (LegacySmsJson.looseEqualsOk(LegacySmsJson.get(rsp, "Code"))
                || LegacySmsJson.looseEqualsOne(LegacySmsJson.get(rsp, "result"))) {
            return new SendResult(1, "ok");
        }
        return new SendResult(101, LegacySmsJson.text(LegacySmsJson.get(rsp, "Message")));
    }

    /** 请求体：{@code Signature=<签名>&k=v&…}（参数已按键名排序）。 */
    static String signedBody(Map<String, String> sorted, String accessKeySecret) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            query.append('&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        String stringToSign = "POST&%2F&" + encode(query.substring(1));
        return "Signature=" + encode(sign(stringToSign, accessKeySecret)) + query;
    }

    static String sign(String stringToSign, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 不可用", null);
        }
    }

    /** 旧 {@code encode()}：{@code urlencode} 后 {@code +}→{@code %20}、{@code *}→{@code %2A}、{@code %7E}→{@code ~}。 */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /** PHP {@code ksort} 对非数字键名按字节比较。 */
    static int byteOrder(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 旧 {@code uniqid(mt_rand(0,0xffff), true)} 的形态：随机前缀 + 13 位十六进制 + {@code .} + 8 位数字（只要求不重复）。 */
    private String nonce(Instant now) {
        return random.nextInt(0x10000) + String.format(Locale.ROOT, "%08x%05x", now.getEpochSecond(), now.getNano() / 1000)
                + "." + String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
    }
}
