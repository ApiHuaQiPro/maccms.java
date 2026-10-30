package com.maccms.backend.openapi.application;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Provide 用到的 PHP 函数的等价实现（B06，16 文档第 3.3、3.5 节），由 PHP 黄金样本锁定。
 */
final class PhpCompat {

    /** PHP 8 的数字字符串（{@code is_numeric}：前后可有空白，十进制，可带小数与指数）。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");
    /** mac_filter_tags 的替换串，按旧代码顺序（application/common.php:2900）。 */
    private static final List<String> FILTERED = List.of("{:", "<script", "<iframe", "<frameset", "<object", "onerror");

    private PhpCompat() {
    }

    /** PHP {@code trim}（默认字符集 {@code " \t\n\r\0\x0B"}）。 */
    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isTrimChar(s.charAt(start))) {
            start++;
        }
        while (end > start && isTrimChar(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    /**
     * PHP {@code urldecode}：{@code +} 为空格，{@code %HH}（大小写十六进制）解码为字节，不完整的 {@code %} 原样保留。结果的字节
     * 须是合法 UTF-8，否则为空（调用方按参数不合法处理；旧版会把非法字节带进 SQL）。
     */
    static Optional<String> urldecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return Optional.of(s);
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write(hex(in[i + 1]) * 16 + hex(in[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    /** PHP {@code empty($string)}：{@code null}、空串与 {@code "0"}。 */
    static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    /** PHP 8 的 {@code $string == $int}：数字字符串按数值比较，其他按字符串比较。 */
    static boolean looseEquals(String s, long n) {
        if (s == null) {
            return n == 0;
        }
        if (NUMERIC.matcher(s).matches()) {
            try {
                return new java.math.BigDecimal(s.strip().replace("\u000B", "")).compareTo(java.math.BigDecimal.valueOf(n)) == 0;
            } catch (NumberFormatException e) {
                return Double.parseDouble(s.strip()) == n;
            }
        }
        return s.equals(Long.toString(n));
    }

    /** {@code strlen}：UTF-8 字节数。 */
    static int byteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * {@code mac_filter_tags}（application/common.php:2898-2915）对整段输出的处理：依次把 {@code {:}、{@code <script}、
     * {@code <iframe}、{@code <frameset}、{@code <object}、{@code onerror} 不区分大小写（ASCII）替换为 {@code *}，
     * 每一项在上一项替换后的结果上进行（与 PHP {@code str_ireplace} 的数组参数相同）。
     */
    static String filterTags(String s) {
        String out = s;
        for (String needle : FILTERED) {
            out = replaceIgnoreAsciiCase(out, needle);
        }
        return out;
    }

    private static String replaceIgnoreAsciiCase(String s, String needle) {
        // PHP str_ireplace 按字节、只对 ASCII 字母不区分大小写
        String lower = asciiLower(s);
        int at = lower.indexOf(needle);
        if (at < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int from = 0;
        while (at >= 0) {
            sb.append(s, from, at).append('*');
            from = at + needle.length();
            at = lower.indexOf(needle, from);
        }
        return sb.append(s, from, s.length()).toString();
    }

    private static String asciiLower(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] >= 'A' && c[i] <= 'Z') {
                c[i] = (char) (c[i] + 32);
            }
        }
        return new String(c);
    }
}
