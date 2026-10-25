package com.maccms.backend.member.application;

import com.maccms.backend.content.application.PhpIntval;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会员财务（B10）用到的 PHP 数值语义：{@code is_numeric}、{@code floatval}、{@code intval(float)}、浮点转字符串，以及删除参数
 * {@code intval(abs($v))}。由 {@code member/finance-php{80,73}-golden.json} 的金额、提现与删除用例锁定。
 */
final class PhpNumbers {

    /** PHP 8 {@code is_numeric}：前导空白、可选符号、十进制（可带小数点）、可选指数、尾随空白。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");
    /** {@code floatval} / 数值前缀（zend_strtod）：前导空白之后最长的十进制数字串。 */
    private static final Pattern PREFIX = Pattern.compile(
            "^[ \\t\\n\\r\\x0B\\f]*([+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?)");

    private PhpNumbers() {
    }

    static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /** {@code floatval($s)}：没有数字前缀时为 0。 */
    static double floatval(String s) {
        Double d = leading(s);
        return d == null ? 0 : d;
    }

    /** 数字前缀的值；没有时为 null（PHP 7.3 的 {@code abs()} 对这种字符串告警并返回 null）。 */
    static Double leading(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = PREFIX.matcher(s);
        if (!m.find()) {
            return null;
        }
        return Double.parseDouble(m.group(1));
    }

    /** {@code intval($float)}：向零取整；NAN、INF 为 0，超出 64 位时取边界。 */
    static long intval(double d) {
        if (!Double.isFinite(d)) {
            return 0;
        }
        return (long) d;
    }

    /**
     * 删除参数的一项 {@code intval(abs($v))}（index/controller/User.php:700-703）：按 PHP 7.3 的结果，数字前缀取绝对值后取整，没有数字
     * 前缀（含空串）为 0（PHP 8 对这种项抛类型错误、返回 500，D-B10-8）。
     */
    static long absIntval(String v) {
        if (v != null && v.chars().allMatch(c -> c >= '0' && c <= '9') && !v.isEmpty()) {
            return PhpIntval.of(v);
        }
        Double d = leading(v);
        return d == null ? 0 : intval(Math.abs(d));
    }

    /**
     * PHP 7.3/8.0 的 {@code (string)$float}（{@code precision=14}，{@code %.14G}）：14 位有效数字、去掉末尾的 0；整数值不带小数点。
     * 只用于金额等常规范围的值（指数 &lt; -4 或 ≥ 15 时用 {@code 1.0E+25} 形式）。
     */
    static String phpString(double d) {
        if (!Double.isFinite(d)) {
            return d > 0 ? "INF" : d < 0 ? "-INF" : "NAN";
        }
        if (d == 0) {
            return d == 0 && 1 / d < 0 ? "-0" : "0";
        }
        BigDecimal v = new BigDecimal(d).round(new MathContext(14)).stripTrailingZeros();
        int exponent = v.precision() - v.scale() - 1;
        if (exponent < -4 || exponent >= 15) {
            String digits = v.unscaledValue().abs().toString();
            String mantissa = digits.charAt(0) + "." + (digits.length() > 1 ? digits.substring(1) : "0");
            return (v.signum() < 0 ? "-" : "") + mantissa + "E" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return v.toPlainString();
    }
}
