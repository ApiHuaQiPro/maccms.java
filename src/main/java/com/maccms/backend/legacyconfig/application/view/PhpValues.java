package com.maccms.backend.legacyconfig.application.view;

import java.math.BigDecimal;

/**
 * 旧代码读取配置值时的 PHP 类型转换（B11 起各模块共用）：{@code (string)$v} 与 {@code empty($v)}。只做转换，不解释含义。
 */
public final class PhpValues {

    private PhpValues() {
    }

    /**
     * PHP {@code (string)$v}：字符串原样；整数十进制；浮点按 PHP 7.1+ 的最短往返形式、整数值不带小数（{@code 3.0} → {@code "3"}）；
     * {@code true} → {@code "1"}、{@code false}/{@code NULL} → {@code ""}；数组 → {@code "Array"}；不存在（Java null）→ {@code ""}。
     */
    public static String text(PhpValue v) {
        return switch (v) {
            case null -> "";
            case PhpValue.PhpString s -> s.value();
            case PhpValue.PhpInt i -> Long.toString(i.value());
            case PhpValue.PhpFloat f -> floatText(f.value());
            case PhpValue.PhpBool b -> b.value() ? "1" : "";
            case PhpValue.PhpNull n -> "";
            case PhpArray a -> "Array";
        };
    }

    /** PHP {@code empty($v)}：不存在、{@code ""}、{@code "0"}、{@code 0}、{@code 0.0}、{@code false}、{@code NULL}、空数组。 */
    public static boolean empty(PhpValue v) {
        return switch (v) {
            case null -> true;
            case PhpValue.PhpString s -> s.value().isEmpty() || s.value().equals("0");
            case PhpValue.PhpInt i -> i.value() == 0;
            case PhpValue.PhpFloat f -> f.value() == 0.0;
            case PhpValue.PhpBool b -> !b.value();
            case PhpValue.PhpNull n -> true;
            case PhpArray a -> a.entries().isEmpty();
        };
    }

    private static String floatText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }
}
