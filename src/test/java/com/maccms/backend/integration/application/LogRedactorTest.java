package com.maccms.backend.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 日志脱敏规则（B23 ④）。所有值都是一看即知的假值。 */
class LogRedactorTest {

    private static final String M = LogRedactor.MASK;

    @Test
    void queryAndFormPairs() {
        assertThat(LogRedactor.redact("a=1&access_token=test-only-tok&sign=ABC&sign_type=MD5&mobile=13800138000&page=2"))
                .isEqualTo("a=1&access_token=" + M + "&sign=" + M + "&sign_type=MD5&mobile=" + M + "&page=2");
        assertThat(LogRedactor.redact("code=123456&state=xyz&user[phone]=13912345678&appkey=test-only"))
                .isEqualTo("code=" + M + "&state=xyz&user[phone]=" + M + "&appkey=" + M);
        assertThat(LogRedactor.redact("https://h.test/cb?client_secret=s1&redirect_uri=x"))
                .isEqualTo("https://h.test/cb?client_secret=" + M + "&redirect_uri=x");
        // 空值保持原样
        assertThat(LogRedactor.redact("token=&a=b")).isEqualTo("token=&a=b");
    }

    @Test
    void jsonMembers() {
        assertThat(LogRedactor.redact("{\"errcode\":0,\"access_token\":\"t-1\",\"expires_in\":7200,\"openid\":\"o1\"}"))
                .isEqualTo("{\"errcode\":0,\"access_token\":\"" + M + "\",\"expires_in\":7200,\"openid\":\"o1\"}");
        assertThat(LogRedactor.redact("{\"Code\" : 482913, \"PhoneNumbers\":\"13800138000\",\"RequestId\":\"r\"}"))
                .isEqualTo("{\"Code\" : \"" + M + "\", \"PhoneNumbers\":\"" + M + "\",\"RequestId\":\"r\"}");
        assertThat(LogRedactor.redact("{\"return_code\":\"SUCCESS\",\"result_code\":\"FAIL\",\"err_code\":\"X\"}"))
                .isEqualTo("{\"return_code\":\"SUCCESS\",\"result_code\":\"FAIL\",\"err_code\":\"X\"}");
        assertThat(LogRedactor.redact("{\"sign\":\"a\\\"b\",\"x\":1}")).isEqualTo("{\"sign\":\"" + M + "\",\"x\":1}");
    }

    @Test
    void xmlElements() {
        assertThat(LogRedactor.redact("<xml><return_code><![CDATA[SUCCESS]]></return_code><sign><![CDATA[S1]]></sign>"
                + "<mch_id>1900000109</mch_id><nonce_str>n</nonce_str><paySign attr=\"1\">p</paySign></xml>"))
                .isEqualTo("<xml><return_code><![CDATA[SUCCESS]]></return_code><sign>" + M + "</sign>"
                        + "<mch_id>" + M + "</mch_id><nonce_str>n</nonce_str><paySign attr=\"1\">" + M + "</paySign></xml>");
    }

    @Test
    void headersBearerUserinfoAndMobiles() {
        assertThat(LogRedactor.redact("Authorization: Basic dGVzdDp0ZXN0\nX-Other: 1"))
                .isEqualTo("Authorization: " + M + "\nX-Other: 1");
        assertThat(LogRedactor.redact("got bearer abc.def-123 from server")).isEqualTo("got bearer " + M + " from server");
        assertThat(LogRedactor.redact("smtp://user:test-only-pw@smtp.mail.test:465/"))
                .isEqualTo("smtp://" + M + "@smtp.mail.test:465/");
        assertThat(LogRedactor.redact("call 13800138000, +86 13912345678 or 8613700001111; id 213800138000 stays"))
                .isEqualTo("call " + M + ", " + M + " or " + M + "; id 213800138000 stays");
    }

    @Test
    void knownSecretValuesAreMaskedAnywhere() {
        String text = "error near test-only-appkey-XYZ in field foo; prefix test-only-appkey";
        assertThat(LogRedactor.redact(text, List.of("test-only-appkey", "test-only-appkey-XYZ", "abc")))
                .isEqualTo("error near " + M + " in field foo; prefix " + M);
        // 太短的已知值不按原文替换
        assertThat(LogRedactor.redact("abc def", List.of("abc"))).isEqualTo("abc def");
    }

    @Test
    void longTextIsTruncatedAndNullIsEmpty() {
        String r = LogRedactor.redact("x".repeat(2000));
        assertThat(r).startsWith("x".repeat(LogRedactor.MAX_LENGTH)).endsWith("…(2000 chars)");
        assertThat(LogRedactor.redact(null)).isEmpty();
    }

    @Test
    void sensitiveNames() {
        for (String n : List.of("key", "appkey", "app_key", "AccessKeySecret", "client_secret", "access_token", "refresh_token",
                "sign", "paySign", "signature", "password", "pwd", "pass", "jsapi_ticket", "code", "verify_code", "captcha",
                "mobile", "phone", "PhoneNumbers", "tel", "mch_id", "mchid", "cookie", "Authorization", "private_key")) {
            assertThat(LogRedactor.isSensitiveName(n)).as(n).isTrue();
        }
        for (String n : List.of("errcode", "err_code", "return_code", "result_code", "status_code", "sub_code", "TemplateCode",
                "sign_type", "SignName", "nonce_str", "openid", "appid", "expires_in", "timestamp", "state", "", "msg")) {
            assertThat(LogRedactor.isSensitiveName(n)).as(n).isFalse();
        }
        assertThat(LogRedactor.isSensitiveName(null)).isFalse();
    }
}
