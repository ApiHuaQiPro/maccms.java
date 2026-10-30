package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 复刻 PHP 语义的小工具，只放有旧代码依据的规则。 */
final class PhpValues {

    /**
     * PHP 8 的数字字符串：可选前导空白、可选符号、整数或小数、可选指数、可选尾随空白
     * （PHP 7 不允许尾随空白；旧配置值是 var_export 输出的纯数字，两者结果相同）。
     */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\u000B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\u000B\\f]*");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    private PhpValues() {
    }

    /** PHP !empty()：null、false、0、0.0、""、"0"、空数组都算空。 */
    static boolean notEmpty(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            String s = v.stringValue();
            return !s.isEmpty() && !"0".equals(s);
        }
        if (v.isContainer()) {
            return !v.isEmpty();
        }
        return true;
    }

    static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /** 字符串之间的 PHP {@code ==}：两边都是数字字符串时按数值比较，否则逐字节相等；{@code null} 与任何非空串都不相等。 */
    static boolean looseEqualsString(String a, String b) {
        if (a == null || b == null) {
            return (a == null ? "" : a).equals(b == null ? "" : b);
        }
        if (isNumeric(a) && isNumeric(b)) {
            return compareNumbers(a, b) == 0;
        }
        return a.equals(b);
    }

    /**
     * PHP SORT_REGULAR 对两个字符串（或 null，按 ""）的比较：都是数字字符串时按数值，否则按字节（strcmp）。
     * 用于 array_multisort 的播放器 sort（application/common.php:1398）。
     */
    static int compareRegular(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        if (isNumeric(x) && isNumeric(y)) {
            return compareNumbers(x, y);
        }
        byte[] bx = x.getBytes(StandardCharsets.UTF_8);
        byte[] by = y.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bx.length, by.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(bx[i] & 0xFF, by[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(bx.length, by.length);
    }

    /**
     * 两个数字字符串按 PHP 规则比较：都是 long 范围内的整数时按整数，否则按 double（超出范围的指数为 ±INF，与 PHP 相同）。
     */
    private static int compareNumbers(String a, String b) {
        Long x = integer(a);
        Long y = integer(b);
        if (x != null && y != null) {
            return Long.compare(x, y);
        }
        return Double.compare(Double.parseDouble(a.strip()), Double.parseDouble(b.strip()));
    }

    private static Long integer(String s) {
        String t = s.strip();
        if (!INTEGER.matcher(t).matches()) {
            return null;
        }
        try {
            return Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
