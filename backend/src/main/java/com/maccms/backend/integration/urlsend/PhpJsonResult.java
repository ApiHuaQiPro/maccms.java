package com.maccms.backend.integration.urlsend;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 推送接口响应的 PHP 取值（旧 {@code $json = json_decode($r, true)} 之后的 {@code !$json}、{@code $json['error']}、字符串拼接与松散比较），
 * 按 PHP 7.3 的规则（30 文档第 3.2 节：PHP 8.0 下旧版推送根本发不出去，以 7.3 为准）。
 */
final class PhpJsonResult {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JsonNode root;

    private PhpJsonResult(JsonNode root) {
        this.root = root;
    }

    /** {@code json_decode($r, true)}；请求失败（{@code $r === false}）或不是合法 JSON 时为 null 值。 */
    static PhpJsonResult decode(byte[] body) {
        if (body == null || body.length == 0) {
            return new PhpJsonResult(null);
        }
        try {
            JsonNode n = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
            return new PhpJsonResult(n == null || n.isMissingNode() ? null : n);
        } catch (JacksonException e) {
            return new PhpJsonResult(null);
        }
    }

    /** PHP {@code !$json}：null、false、0、0.0、""、"0"、空数组为真。 */
    boolean falsy() {
        return !truthy(root);
    }

    /** {@code $json[$key]}：顶层不是数组（对象）时取不到，按 null。 */
    JsonNode get(String key) {
        return root != null && root.isObject() ? root.get(key) : null;
    }

    static boolean truthy(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.decimalValue().signum() != 0;
        }
        if (n.isString()) {
            String s = n.stringValue();
            return !s.isEmpty() && !s.equals("0");
        }
        return !n.isEmpty();
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

    /**
     * 与整数 0 比较时的数值（PHP 7 的 {@code $v == 0}、{@code $v > 0}）：null/false 为 0，true 为 1，数字原值，字符串取开头的数字部分
     * （{@code "abc"} 为 0）。数组与整数比较时总是更大，返回 null 由调用方处理。
     */
    static BigDecimal number(JsonNode n) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        if (n.isBoolean()) {
            return n.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isString()) {
            return leadingNumber(n.stringValue());
        }
        return null;
    }

    /** PHP {@code (int)$v}（array_slice 的长度）。 */
    static long intval(JsonNode n) {
        if (n != null && n.isContainer()) {
            return n.isEmpty() ? 0 : 1;
        }
        BigDecimal d = number(n);
        try {
            return d == null ? 0 : d.toBigInteger().longValueExact();
        } catch (ArithmeticException e) {
            return 0;
        }
    }

    /** 字符串开头的十进制数（可带符号与小数点），没有数字时为 0。 */
    static BigDecimal leadingNumber(String s) {
        int i = 0;
        while (i < s.length() && " \t\n\r\u000B\f".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        StringBuilder num = new StringBuilder();
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            num.append(s.charAt(i));
            i++;
        }
        int digits = 0;
        StringBuilder intPart = new StringBuilder();
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            intPart.append(s.charAt(i++));
            digits++;
        }
        StringBuilder frac = new StringBuilder();
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                frac.append(s.charAt(i++));
                digits++;
            }
        }
        if (digits == 0) {
            return BigDecimal.ZERO;
        }
        num.append(intPart.isEmpty() ? "0" : intPart);
        if (!frac.isEmpty()) {
            num.append('.').append(frac);
        }
        return new BigDecimal(num.toString());
    }
}
