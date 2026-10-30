package com.maccms.backend.collect.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * PHP {@code json_decode($s, true)}（默认深度 512）的等价解码（B18，26 文档第 3.1 节），结果的表示见 {@link ProvideValues}。按 PHP 的
 * JSON 解析器：
 * <ul>
 * <li>只认 RFC 8259 语法；空白只有空格、{@code \t}、{@code \n}、{@code \r}（UTF-8 BOM 不是空白，解析失败）；顶层之后不能有其他内容；</li>
 * <li>字符串必须是合法 UTF-8，不能含 U+0000–U+001F 原字符；{@code \}{@code u} 转义的代理项必须成对；</li>
 * <li>整数在 int64 范围内是整数，超出时是浮点；有小数或指数的是浮点（{@code -0} 是整数 0）；</li>
 * <li>对象的键按 PHP 数组规则规范化，重复的键保留第一次出现的位置、取最后一次的值；</li>
 * <li>数组与对象最多嵌套 {@value #MAX_DEPTH} 层（PHP 默认深度 512：容器层数达到 512 即失败，黄金用例 {@code vod-json-depth-*}）。</li>
 * </ul>
 * 与 PHP 不同：结果为无穷大的数（如 {@code 1e400}，PHP 得到 INF）视为解析失败（D-B18-5）。
 *
 * <p>{@link #encode} 把同样表示的值写成 JSON（预览缓存用）：键为 0..n-1 连续整数的数组写成 JSON 数组，否则写成对象；
 * {@code decode(encode(v))} 与 {@code v} 相同。
 */
public final class ProvideJson {

    static final int MAX_DEPTH = 511;

    /** 解析失败。 */
    public static final class MalformedJsonException extends Exception {
        MalformedJsonException(String what) {
            super(what, null, false, false);
        }
    }

    private final byte[] b;
    private int p;
    private int depth;

    private ProvideJson(byte[] b) {
        this.b = b;
    }

    public static Object decode(byte[] json) throws MalformedJsonException {
        ProvideJson d = new ProvideJson(json);
        d.ws();
        Object v = d.value();
        d.ws();
        if (d.p != d.b.length) {
            throw new MalformedJsonException("syntax");
        }
        return v;
    }

    private void ws() {
        while (p < b.length && (b[p] == ' ' || b[p] == '\t' || b[p] == '\n' || b[p] == '\r')) {
            p++;
        }
    }

    private Object value() throws MalformedJsonException {
        if (p >= b.length) {
            throw new MalformedJsonException("syntax");
        }
        return switch (b[p]) {
            case '{' -> object();
            case '[' -> list();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object v) throws MalformedJsonException {
        for (int i = 0; i < word.length(); i++) {
            if (p + i >= b.length || b[p + i] != word.charAt(i)) {
                throw new MalformedJsonException("syntax");
            }
        }
        p += word.length();
        return v;
    }

    private Map<Object, Object> object() throws MalformedJsonException {
        enter();
        p++;
        Map<Object, Object> m = ProvideValues.array();
        ws();
        if (p < b.length && b[p] == '}') {
            p++;
            depth--;
            return m;
        }
        while (true) {
            ws();
            if (p >= b.length || b[p] != '"') {
                throw new MalformedJsonException("syntax");
            }
            String k = string();
            ws();
            if (p >= b.length || b[p] != ':') {
                throw new MalformedJsonException("syntax");
            }
            p++;
            ws();
            m.put(ProvideValues.key(k), value());
            ws();
            if (p < b.length && b[p] == ',') {
                p++;
                continue;
            }
            if (p < b.length && b[p] == '}') {
                p++;
                depth--;
                return m;
            }
            throw new MalformedJsonException("syntax");
        }
    }

    private Map<Object, Object> list() throws MalformedJsonException {
        enter();
        p++;
        Map<Object, Object> m = ProvideValues.array();
        ws();
        if (p < b.length && b[p] == ']') {
            p++;
            depth--;
            return m;
        }
        long i = 0;
        while (true) {
            ws();
            m.put(i++, value());
            ws();
            if (p < b.length && b[p] == ',') {
                p++;
                continue;
            }
            if (p < b.length && b[p] == ']') {
                p++;
                depth--;
                return m;
            }
            throw new MalformedJsonException("syntax");
        }
    }

    private void enter() throws MalformedJsonException {
        if (++depth > MAX_DEPTH) {
            throw new MalformedJsonException("depth");
        }
    }

    private String string() throws MalformedJsonException {
        p++;
        StringBuilder sb = new StringBuilder();
        int runStart = p;
        while (true) {
            if (p >= b.length) {
                throw new MalformedJsonException("syntax");
            }
            int c = b[p] & 0xff;
            if (c == '"') {
                flush(sb, runStart, p);
                p++;
                return sb.toString();
            }
            if (c < 0x20) {
                throw new MalformedJsonException("control character");
            }
            if (c != '\\') {
                p++;
                continue;
            }
            flush(sb, runStart, p);
            p++;
            if (p >= b.length) {
                throw new MalformedJsonException("syntax");
            }
            char e = (char) b[p++];
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    char u = hex4();
                    if (Character.isHighSurrogate(u)) {
                        if (p + 1 < b.length && b[p] == '\\' && b[p + 1] == 'u') {
                            p += 2;
                            char lo = hex4();
                            if (!Character.isLowSurrogate(lo)) {
                                throw new MalformedJsonException("utf16");
                            }
                            sb.append(u).append(lo);
                        } else {
                            throw new MalformedJsonException("utf16");
                        }
                    } else if (Character.isLowSurrogate(u)) {
                        throw new MalformedJsonException("utf16");
                    } else {
                        sb.append(u);
                    }
                }
                default -> throw new MalformedJsonException("syntax");
            }
            runStart = p;
        }
    }

    private char hex4() throws MalformedJsonException {
        if (p + 4 > b.length) {
            throw new MalformedJsonException("syntax");
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(b[p + i], 16);
            if (d < 0) {
                throw new MalformedJsonException("syntax");
            }
            v = v * 16 + d;
        }
        p += 4;
        return (char) v;
    }

    private void flush(StringBuilder sb, int from, int to) throws MalformedJsonException {
        if (from == to) {
            return;
        }
        try {
            sb.append(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, from, to - from)));
        } catch (CharacterCodingException e) {
            throw new MalformedJsonException("utf8");
        }
    }

    private Object number() throws MalformedJsonException {
        int start = p;
        boolean real = false;
        if (p < b.length && b[p] == '-') {
            p++;
        }
        if (p < b.length && b[p] == '0') {
            p++;
        } else if (p < b.length && b[p] >= '1' && b[p] <= '9') {
            while (p < b.length && b[p] >= '0' && b[p] <= '9') {
                p++;
            }
        } else {
            throw new MalformedJsonException("syntax");
        }
        if (p < b.length && b[p] == '.') {
            real = true;
            p++;
            if (p >= b.length || b[p] < '0' || b[p] > '9') {
                throw new MalformedJsonException("syntax");
            }
            while (p < b.length && b[p] >= '0' && b[p] <= '9') {
                p++;
            }
        }
        if (p < b.length && (b[p] == 'e' || b[p] == 'E')) {
            real = true;
            p++;
            if (p < b.length && (b[p] == '+' || b[p] == '-')) {
                p++;
            }
            if (p >= b.length || b[p] < '0' || b[p] > '9') {
                throw new MalformedJsonException("syntax");
            }
            while (p < b.length && b[p] >= '0' && b[p] <= '9') {
                p++;
            }
        }
        String s = new String(b, start, p - start, StandardCharsets.US_ASCII);
        if (!real) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException overflow) {
                // 超出 int64：PHP 得到浮点
            }
        }
        double d = Double.parseDouble(s);
        if (Double.isInfinite(d)) {
            throw new MalformedJsonException("infinite");
        }
        return d;
    }

    // ------------------------------------------------------------------ 编码（预览缓存）

    public static String encode(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Long l -> sb.append(l);
            case Double d -> {
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("非有限浮点");
                }
                // 带小数点或指数，解回来仍是浮点
                sb.append(Double.toString(d));
            }
            case Boolean bo -> sb.append(bo ? "true" : "false");
            case Map<?, ?> m -> {
                if (isList(m)) {
                    sb.append('[');
                    boolean first = true;
                    for (Object x : m.values()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        write(sb, x);
                    }
                    sb.append(']');
                } else {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        quote(sb, String.valueOf(e.getKey()));
                        sb.append(':');
                        write(sb, e.getValue());
                    }
                    sb.append('}');
                }
            }
            case List<?> l -> {
                Map<Object, Object> m = ProvideValues.array();
                long i = 0;
                for (Object x : l) {
                    m.put(i++, x);
                }
                write(sb, m);
            }
            default -> throw new IllegalArgumentException("不是 PHP 值：" + v.getClass().getName());
        }
    }

    /** 键是否为 0..n-1 连续整数（PHP {@code json_encode} 写成 JSON 数组的条件）。 */
    static boolean isList(Map<?, ?> m) {
        long i = 0;
        for (Object k : m.keySet()) {
            if (!(k instanceof Long l) || l != i++) {
                return false;
            }
        }
        return true;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c) && !validPair(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static boolean validPair(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
