package com.maccms.backend.openapi.application;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信公众号接口里旧代码依赖的 PHP 7.3 比较规则（B27，29 文档第 4 节；黄金样本以 PHP 7.3 为准，与 8.0 的差异见该节）。
 */
final class WechatPhp {

    /** PHP 7 数字串：前导空白、可选符号、整数或小数、可选指数（不允许尾随空白）。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    /** 非数字串与数字比较时取的前导数字部分（PHP 7 的字符串转数字）。 */
    private static final Pattern LEADING = Pattern.compile(
            "^[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private WechatPhp() {
    }

    static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /**
     * 字符串与整数宽松比较时字符串一侧的数值（PHP 7：{@code '2' == 2}、{@code 'abc' == 0}、{@code '1abc' == 1}），
     * 用于 {@code $conf['status'] == 0}、{@code $conf['bofang'] > 0} 一类判断。
     */
    static double number(String s) {
        if (s == null) {
            return 0;
        }
        Matcher m = LEADING.matcher(s);
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group().strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * PHP 7 {@code sort()} 默认（SORT_REGULAR）对两个字符串的比较：都是数字串时按数值，否则按字节（strcmp）。
     */
    static int compareRegular(String a, String b) {
        if (isNumeric(a) && isNumeric(b)) {
            return Double.compare(Double.parseDouble(a.strip()), Double.parseDouble(b.strip()));
        }
        return compareBytes(a, b);
    }

    /** 按 UTF-8 字节比较（strcmp，即 {@code sort($a, SORT_STRING)}）。 */
    static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(x[i] & 0xFF, y[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
