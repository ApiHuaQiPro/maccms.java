package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 后台内容管理里旧代码的 PHP 8 宽松比较（B13）：{@code in_array($v, ['0','1'])}、{@code $v == 1} 一类。PHP 7.3 与 8.0 只在"数字后带尾随空白"
 * 一类罕见取值上不同（7.3 不算数字串），新版按 PHP 8。
 */
public final class PhpLoose {

    /** PHP 8 数字串：前后可有空白（" \t\n\r\v\f"），可选符号，整数或小数，可选指数。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");

    private PhpLoose() {
    }

    /** PHP 8 {@code is_numeric($s)}。 */
    public static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /** 字符串参数与数字字符串常量的宽松相等（{@code $v == 'n'}）：两边都是数字串时按数值比较。 */
    public static boolean equalsNumber(byte[] v, long n) {
        if (v == null) {
            return false;
        }
        String s = new String(v, StandardCharsets.ISO_8859_1);
        if (!isNumeric(s)) {
            return false;
        }
        try {
            return Double.parseDouble(s.strip()) == n;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code $v == 1} 一类判断（{@code $data['uptime']==1}）：缺失（null）时为假。 */
    public static boolean equalsOne(byte[] v) {
        return equalsNumber(v, 1);
    }
}
