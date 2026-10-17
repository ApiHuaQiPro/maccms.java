package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 与 PHP {@code json_encode($v)}（默认选项）逐字节相同的编码（B05：{@code playerconfig.js}、{@code player_aaaa}）：
 * 字符串中的 {@code "}、{@code \}、{@code /} 转义，{@code \b \f \n \r \t} 用短写，其余 U+0000–U+001F 与全部非 ASCII 字符写成
 * 小写十六进制的 {@code \}{@code uXXXX}（U+10000 以上写成代理对），U+007F 原样。PHP 数组只有键为 0..n-1 连续整数时编码为 JSON 数组，
 * 否则为对象（整数键写成字符串）。
 *
 * <p>{@link #encodeForScript} 另把 {@code < > & '} 与 U+2028、U+2029 写成转义（与 PHP 的 {@code JSON_HEX_TAG|JSON_HEX_AMP|
 * JSON_HEX_APOS} 相同的字符），结果放进 HTML {@code <script>} 元素内也不会提前结束元素或被当作注释；解析后的值与默认编码完全相同。
 *
 * <p>支持的值：{@code null}、{@link String}、{@link Boolean}、{@link Integer}/{@link Long}、{@link Map}（键为字符串，按迭代顺序，
 * 编码为对象）、{@link List}（编码为数组），以及旧配置的 {@link PhpValue}。其他类型抛 {@link IllegalArgumentException}。
 */
public final class PhpJson {

    private PhpJson() {
    }

    /** PHP {@code json_encode($v)} 的结果。 */
    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false);
        return sb.toString();
    }

    /** 可直接放进 {@code <script>} 元素的 JSON（见类注释）。 */
    public static String encodeForScript(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean script) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s, script);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("对象键必须是字符串");
                    }
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, key, script);
                    sb.append(':');
                    write(sb, e.getValue(), script);
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, list.get(i), script);
                }
                sb.append(']');
            }
            case PhpValue p -> php(sb, p, script);
            default -> throw new IllegalArgumentException("不支持的类型");
        }
    }

    private static void php(StringBuilder sb, PhpValue v, boolean script) {
        switch (v) {
            case PhpValue.PhpString s -> string(sb, s.value(), script);
            case PhpValue.PhpInt i -> sb.append(i.value());
            case PhpValue.PhpBool b -> sb.append(b.value() ? "true" : "false");
            case PhpValue.PhpNull n -> sb.append("null");
            case PhpValue.PhpFloat f -> sb.append(phpFloat(f.value()));
            case PhpArray a -> {
                if (isList(a)) {
                    sb.append('[');
                    boolean first = true;
                    for (PhpValue item : a.entries().values()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        php(sb, item, script);
                    }
                    sb.append(']');
                } else {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        string(sb, String.valueOf(e.getKey()), script);
                        sb.append(':');
                        php(sb, e.getValue(), script);
                    }
                    sb.append('}');
                }
            }
        }
    }

    /** 键为 0..n-1 的连续整数（按顺序）：PHP json_encode 编码为数组。空数组也是数组（{@code []}）。 */
    private static boolean isList(PhpArray a) {
        long expected = 0;
        for (Object k : a.entries().keySet()) {
            if (!(k instanceof Long l) || l != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    /**
     * PHP 7.1+（serialize_precision=-1）的浮点输出：最短往返表示；整数值带 {@code .0}。旧配置里极少出现浮点，这里只为完整。
     */
    static String phpFloat(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("PHP json_encode 不接受 NAN/INF");
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return BigDecimal.valueOf(d).setScale(1).toPlainString();
        }
        String s = Double.toString(d);
        if (s.contains("E")) {
            // Java 的 1.0E20 → PHP 的 1.0e+20
            String[] parts = s.split("E");
            String exp = parts[1].startsWith("-") ? parts[1] : "+" + parts[1];
            return parts[0] + "e" + exp;
        }
        return s;
    }

    private static void string(StringBuilder sb, String s, boolean script) {
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
                default -> {
                    if (c < 0x20 || c > 0x7e && c != 0x7f) {
                        unicode(sb, c);
                    } else if (script && (c == '<' || c == '>' || c == '&' || c == '\'')) {
                        unicode(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void unicode(StringBuilder sb, char c) {
        String hex = Integer.toHexString(c);
        sb.append("\\u");
        for (int k = hex.length(); k < 4; k++) {
            sb.append('0');
        }
        sb.append(hex);
    }
}
