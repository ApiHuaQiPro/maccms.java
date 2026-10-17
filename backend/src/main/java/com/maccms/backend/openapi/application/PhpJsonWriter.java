package com.maccms.backend.openapi.application;

import java.util.List;
import java.util.Map;

/**
 * 与 PHP {@code json_encode($v, JSON_UNESCAPED_UNICODE)} 逐字节相同的编码（B06，Provide.php:136、:446 等）：字符串中的
 * {@code "}、{@code \}、{@code /} 转义，{@code \b \f \n \r \t} 用短写，其余 U+0000–U+001F 写成小写十六进制的
 * {@code \}{@code u00XX}，U+2028/U+2029 写成 {@code \}{@code u2028}/{@code \}{@code u2029}（未加 JSON_UNESCAPED_LINE_TERMINATORS），
 * 其他字符（含 U+007F 与全部非 ASCII）原样输出。
 *
 * <p>支持的值：{@code null}、{@link String}、{@link Boolean}、{@link Integer}/{@link Long}、{@link Map}（键为字符串，按迭代顺序，
 * 编码为对象）、{@link List}（编码为数组）。
 */
final class PhpJsonWriter {

    private PhpJsonWriter() {
    }

    static String encode(Object value) {
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
