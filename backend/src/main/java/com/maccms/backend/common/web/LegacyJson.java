package com.maccms.backend.common.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 与 PHP {@code json_encode($v, JSON_UNESCAPED_UNICODE)} 逐字节相同的编码（B06：Provide，api/controller/Provide.php:136；
 * ThinkPHP {@code json()} 响应，thinkphp/library/think/response/Json.php 的默认选项）。字符串中的 {@code "}、{@code \}、{@code /}
 * 转义，{@code \b \f \n \r \t} 用短写，其余 U+0000–U+001F 写成小写十六进制的 {@code \}{@code u00XX}，U+2028/U+2029 写成
 * {@code \}{@code u2028}/{@code \}{@code u2029}（未加 JSON_UNESCAPED_LINE_TERMINATORS），其他字符（含 U+007F 与全部非 ASCII）原样。
 * 浮点按 PHP 7.1+ 的 {@code serialize_precision=-1}：最短往返表示，整数值不带小数部分（{@code 10.0} → {@code 10}）。
 *
 * <p>支持的值：{@code null}、{@link String}、{@link Boolean}、{@link Integer}/{@link Long}、有限的 {@link Double}、
 * {@link Map}（键按迭代顺序转成字符串，编码为对象）、{@link List}（编码为数组）。其他类型抛 {@link IllegalArgumentException}。
 */
public final class LegacyJson {

    private LegacyJson() {
    }

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Double d -> sb.append(phpFloat(d));
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, list.get(i));
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("不支持的类型");
        }
    }

    /** PHP 7.1+ json_encode 的浮点输出（serialize_precision=-1）。 */
    static String phpFloat(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("PHP json_encode 不接受 NAN/INF");
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        String s = Double.toString(d);
        int e = s.indexOf('E');
        if (e < 0) {
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        }
        // Java 1.0E25 / 1.5E-7 → PHP 1.0e+25 / 1.5e-7
        String mantissa = s.substring(0, e);
        String exp = s.substring(e + 1);
        return mantissa + "e" + (exp.startsWith("-") ? exp : "+" + exp);
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
