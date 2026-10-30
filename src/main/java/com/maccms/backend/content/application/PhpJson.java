package com.maccms.backend.content.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧代码保存与读取 JSON 列（如 {@code type_extend}）时的 PHP {@code json_encode($v)}（默认选项）与 {@code json_decode($s, true)}（B13）。
 *
 * <p>{@link #encode}：与 PHP 默认选项逐字节相同——非 ASCII 字符写成小写十六进制的 {@code \}{@code uXXXX}（4 字节字符写成代理对），
 * {@code /} 写成 {@code \/}，{@code " \ \b \f \n \r \t} 用短写，其余控制字符 {@code \}{@code u00XX}。值只能是字符串、
 * {@link Map}（对象，键按迭代顺序）与 {@link List}（数组）。
 */
public final class PhpJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PhpJson() {
    }

    public static String encode(Object value) {
        return encode(value, false);
    }

    /** {@code hexTag} 为真时同 {@code json_encode($v, JSON_HEX_TAG)}：尖括号写成大写十六进制的 Unicode 转义（003C、003E）。 */
    public static String encode(Object value, boolean hexTag) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, hexTag);
        return sb.toString();
    }

    /**
     * 旧 {@code json_encode(json_decode($s, true))}（ueditor 的 {@code action=config}）：保留数字与布尔的类型重新编码；不是合法 JSON 时为
     * {@code null}。
     */
    public static String reencode(String json) {
        try {
            return encode(tree(JSON.readTree(json)), false);
        } catch (JacksonException e) {
            return "null";
        }
    }

    private static Object tree(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.properties().forEach(e -> m.put(e.getKey(), tree(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(tree(x)));
            return l;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        return n.asString();
    }

    private static void write(StringBuilder sb, Object v, boolean hexTag) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s, hexTag);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Double d -> sb.append(PhpNumbers.text(d));
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, String.valueOf(e.getKey()), hexTag);
                    sb.append(':');
                    write(sb, e.getValue(), hexTag);
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, list.get(i), hexTag);
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("不支持的类型");
        }
    }

    private static void string(StringBuilder sb, String s, boolean hexTag) {
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
                case '<' -> sb.append(hexTag ? "\\u003C" : "<");
                case '>' -> sb.append(hexTag ? "\\u003E" : ">");
                default -> {
                    if (c < 0x20 || c >= 0x80) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * PHP {@code json_decode($s, true)}：对象 → 按键顺序的 {@link LinkedHashMap}，数组 → {@link List}，字符串、数字、布尔原样
     * （数字为 PHP 打印时的文字），null 为 null。不是合法 JSON 时返回 null（与旧版相同）。
     */
    public static Object decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return convert(JSON.readTree(s));
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Object convert(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.properties().forEach(e -> m.put(e.getKey(), convert(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(convert(x)));
            return l;
        }
        if (n.isBoolean()) {
            return n.booleanValue() ? "1" : "";
        }
        if (n.isNumber()) {
            return n.isIntegralNumber() ? n.asString() : n.numberValue().toString();
        }
        return n.asString();
    }
}
