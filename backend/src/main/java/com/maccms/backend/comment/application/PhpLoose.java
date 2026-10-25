package com.maccms.backend.comment.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.regex.Pattern;

/** 读旧配置开关时的 PHP 8 宽松比较（{@code $config['x'] == 1}）与 {@code intval}、{@code is_numeric}（B11）。 */
final class PhpLoose {

    /** PHP 8 数字串：前后可有空白（" \t\n\r\v\f"），可选符号，整数或小数，可选指数。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");

    private PhpLoose() {
    }

    static boolean isNumeric(String s) {
        return NUMERIC.matcher(s).matches();
    }

    /** PHP 8 的 {@code $v == $n}（$n 为整数）。 */
    static boolean equalsInt(PhpValue v, long n) {
        return switch (v) {
            case null -> n == 0;
            case PhpValue.PhpNull x -> n == 0;
            case PhpValue.PhpBool b -> b.value() == (n != 0);
            case PhpValue.PhpInt i -> i.value() == n;
            case PhpValue.PhpFloat f -> f.value() == n;
            case PhpValue.PhpString s -> isNumeric(s.value()) && Double.parseDouble(s.value().strip()) == n;
            case PhpArray a -> false;
        };
    }

    /** PHP {@code intval($v)}。 */
    static long intval(PhpValue v) {
        return switch (v) {
            case null -> 0;
            case PhpValue.PhpNull x -> 0;
            case PhpValue.PhpBool b -> b.value() ? 1 : 0;
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> Double.isFinite(f.value()) ? (long) f.value() : 0;
            case PhpValue.PhpString s -> PhpIntval.of(s.value());
            case PhpArray a -> a.entries().isEmpty() ? 0 : 1;
        };
    }
}
