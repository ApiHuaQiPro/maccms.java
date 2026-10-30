package com.maccms.backend.integration.sms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 短信通道响应的 PHP 取值（{@code json_decode($r, true)} 之后的 {@code $rsp['x']}、字符串拼接与松散比较），按 PHP 7.3。 */
final class LegacySmsJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LegacySmsJson() {
    }

    /** {@code json_decode($r, true)} 的顶层对象；请求失败、不是合法 JSON 或顶层不是对象时为 null（之后取任何键都是 null）。 */
    static JsonNode decodeObject(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
            return n != null && n.isObject() ? n : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    static JsonNode get(JsonNode obj, String key) {
        return obj == null ? null : obj.get(key);
    }

    /** PHP 字符串拼接时的转换：null/false 为空串，true 为 1，数组为 {@code Array}。 */
    static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isBoolean()) {
            return n.booleanValue() ? "1" : "";
        }
        if (n.isString()) {
            return n.stringValue();
        }
        if (n.isIntegralNumber()) {
            return n.bigIntegerValue().toString();
        }
        if (n.isNumber()) {
            BigDecimal d = n.decimalValue().stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        }
        return "Array";
    }

    /** PHP 7 {@code $v == 'OK'}：字符串逐字比较；true 相等；数字与非数字串比较时串按 0。 */
    static boolean looseEqualsOk(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isString()) {
            return n.stringValue().equals("OK");
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.decimalValue().signum() == 0;
        }
        return false;
    }

    /** PHP 7 {@code $v == 1}：数字、数字串按数值；true 相等。 */
    static boolean looseEqualsOne(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.decimalValue().compareTo(BigDecimal.ONE) == 0;
        }
        if (n.isString()) {
            String s = n.stringValue().strip();
            try {
                return !s.isEmpty() && new BigDecimal(s).compareTo(BigDecimal.ONE) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
