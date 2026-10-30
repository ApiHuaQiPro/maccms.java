package com.maccms.backend.content.application;

/**
 * PHP {@code intval($string)}（十进制）对字符串的结果，PHP 7.1 起与 8.x 相同（{@code zval_get_long} →
 * {@code is_numeric_string} 允许尾部多余字符 → 小数或溢出时 {@code zend_dval_to_lval_cap}）。用于旧 {@code mac_param_url}
 * 中按 {@code intval} 读取的 URL 参数（{@code tid}、{@code level}，application/common.php:1566-1578）与旧模板标签属性中按整数绑定的列表。
 * 规则由 PHP 黄金向量锁定（src/test/resources/content/vodtag/php-golden.json 的 {@code intval}）：
 * <ul>
 * <li>跳过开头的 {@code " \t\n\r\v\f"}；可选正负号；十进制整数部分、可选小数部分、可选指数（{@code e}/{@code E} 后须有数字）；
 * 其后的任何字符都被忽略（{@code "6abc"} 为 6，{@code "1,2"} 为 1）；</li>
 * <li>没有任何数字（{@code ""}、{@code "abc"}、{@code "0x1A"} 的 {@code x} 之前只有 0 → 0，全角数字 → 0）为 0；</li>
 * <li>带小数或指数时按浮点数向 0 截断（{@code "1e3"} 为 1000，{@code "1.9"} 为 1）；超出 64 位整数范围时取
 * {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}；无穷大为 0。</li>
 * </ul>
 */
public final class PhpIntval {

    private PhpIntval() {
    }

    public static long of(String s) {
        if (s == null) {
            return 0;
        }
        int n = s.length();
        int i = 0;
        while (i < n && isSpace(s.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int intStart = i;
        while (i < n && digit(s.charAt(i))) {
            i++;
        }
        int intDigits = i - intStart;
        boolean isDouble = false;
        int fracDigits = 0;
        if (i < n && s.charAt(i) == '.') {
            int j = i + 1;
            while (j < n && digit(s.charAt(j))) {
                j++;
            }
            fracDigits = j - i - 1;
            if (intDigits > 0 || fracDigits > 0) {
                isDouble = true;
                i = j;
            }
        }
        if (intDigits == 0 && fracDigits == 0) {
            return 0;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            if (j < n && digit(s.charAt(j))) {
                while (j < n && digit(s.charAt(j))) {
                    j++;
                }
                isDouble = true;
                i = j;
            }
        }
        String number = s.substring(start, i);
        if (!isDouble) {
            try {
                return Long.parseLong(number.startsWith("+") ? number.substring(1) : number);
            } catch (NumberFormatException overflow) {
                // 位数超出整数范围时 PHP 按浮点数处理，再饱和到整数上下限
                return number.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        double d = Double.parseDouble(number);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0;
        }
        if (d >= 0x1p63) {
            return Long.MAX_VALUE;
        }
        if (d < -0x1p63) {
            return Long.MIN_VALUE;
        }
        return (long) d;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
