package com.maccms.backend.integration.oauth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** QQ/微信授权接口响应的解析：JSON、QQ 的 JSONP 包装 {@code callback( {...} );}。 */
final class OAuthJson {

    /** openid 的形态：QQ 为 32 位十六进制，微信为字母数字与 {@code -_}；最长 40（{@code user_openid_*} 列宽 varchar(40)）。 */
    static final Pattern OPENID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private OAuthJson() {
    }

    /** 顶层 JSON 对象；QQ 的 JSONP 去掉包装后再解析；不是对象时为 null。 */
    static JsonNode object(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String s = new String(body, StandardCharsets.UTF_8).strip();
        if (s.startsWith("callback(")) {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a < 0 || b < a) {
                return null;
            }
            s = s.substring(a, b + 1);
        }
        if (!s.startsWith("{")) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 字段的文字值（字符串或数字）；不存在、null、对象或数组时为空串。 */
    static String text(JsonNode obj, String key) {
        JsonNode n = obj == null ? null : obj.get(key);
        if (n == null || n.isNull() || n.isContainer()) {
            return "";
        }
        return n.asString();
    }

    /** 错误码字段为 0（数字或 {@code "0"}）；{@code absentIsZero} 时字段不存在也算 0。 */
    static boolean zero(JsonNode obj, String key, boolean absentIsZero) {
        JsonNode n = obj == null ? null : obj.get(key);
        if (n == null || n.isNull()) {
            return absentIsZero;
        }
        if (n.isNumber()) {
            return n.isIntegralNumber() && n.longValue() == 0;
        }
        return n.isString() && n.asString().equals("0");
    }

    static boolean validOpenid(String openid) {
        return openid != null && OPENID.matcher(openid).matches();
    }
}
