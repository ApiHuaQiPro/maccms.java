package com.maccms.backend.integration.pay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 旧支付代码用到的 PHP 字符串语义（PHP 7.3 为准，33 文档第 3 节）。字符串一律是"字节串"：每个字符即一个字节（ISO-8859-1），
 * 与 PHP 的字符串同样按字节拼接、排序与求 MD5；UTF-8 文本经 {@link #bytes(String)} 转成字节串。
 */
final class PhpPay {

    /** {@code ksort}：键都不是数字串时等同按字节比较（旧接口的参数名都是字母）。 */
    static final Comparator<String> KSORT = (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.ISO_8859_1),
            b.getBytes(StandardCharsets.ISO_8859_1));

    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private PhpPay() {
    }

    /** UTF-8 文本 → 字节串。 */
    static String bytes(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /** 字节串 → UTF-8 文本（非法序列替换为 U+FFFD）。 */
    static String text(String bytes) {
        return new String(bytes.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    static String md5(String bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(bytes.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 常量时间比较（签名比较用严格相等，旧版是 {@code ==}，33 文档 D-B24-2）。 */
    static boolean same(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.ISO_8859_1),
                actual.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** PHP {@code trim()}：去掉两端的 {@code " \t\n\r\0\x0B"}。 */
    static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && trimmed(s.charAt(a))) {
            a++;
        }
        while (b > a && trimmed(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    /** PHP {@code urlencode()}：字母数字与 {@code -_.} 不变，空格为 {@code +}，其余 {@code %XX}（大写）。 */
    static String urlencode(String bytes) {
        StringBuilder sb = new StringBuilder(bytes.length() * 3);
        for (int i = 0; i < bytes.length(); i++) {
            char c = bytes.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4 & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** PHP 7 {@code is_numeric()} 对字符串（允许前导空白，不允许尾随空白）。 */
    static boolean isNumeric(String s) {
        return NUMERIC.matcher(s).matches();
    }

    /**
     * PHP 7 中浮点数转字符串（{@code precision = 14}，即 {@code %.14G}）：用于微信 {@code total_fee = $price * 100}。
     * 金额最多 {@code decimal(12,2)}，乘 100 后不到 1e14，不会出现指数形式。
     */
    static String floatText(double d) {
        if (d == 0) {
            return "0";
        }
        BigDecimal v = new BigDecimal(d).round(new MathContext(14)).stripTrailingZeros();
        return v.toPlainString();
    }

    /** 按 {@link #KSORT} 排序的副本。 */
    static <V> TreeMap<String, V> ksort(Map<String, V> m) {
        TreeMap<String, V> t = new TreeMap<>(KSORT);
        t.putAll(m);
        return t;
    }
}
