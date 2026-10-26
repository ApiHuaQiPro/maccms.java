package com.maccms.backend.admin.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 旧模板与控制器里的 PHP 8 宽松比较 {@code ==}（ThinkPHP 模板的 {@code eq}/{@code neq}）与 {@code is_numeric}（B15，24 文档第 4.2 节）。
 * 操作数可以是旧配置的 {@link PhpValue}、{@link String}、{@link Long}/{@link Integer}、{@link Boolean} 或 null（PHP 的 null）。
 *
 * <p>PHP 8：null 与字符串比较时 null 当作空串，与其他标量比较时转成布尔；布尔与任何值比较时另一边转成布尔；两个数字字符串（或数字与数字字符串）
 * 按数值比较，其余字符串按字节比较；整数与非数字字符串比较时整数转成字符串（PHP 7 是字符串转成整数，{@code 'abc' == 0} 在 7 里为真、在 8 里为假；
 * 旧配置由表单保存，取值都是数字字符串或空串，两版结果相同）；数组只与数组、null、布尔比较。
 */
public final class PhpLoose {

    /** PHP 8 {@code is_numeric}：可带前后空白（空格、\t、\n、\r、\v、\f）、正负号、小数与指数，不接受十六进制与空串。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");

    private PhpLoose() {
    }

    public static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /** PHP 8 {@code $a == $b}。 */
    public static boolean equals(Object a, Object b) {
        Object x = normalize(a);
        Object y = normalize(b);
        if (x instanceof PhpArray || y instanceof PhpArray) {
            if (x instanceof PhpArray xa && y instanceof PhpArray ya) {
                return arraysEqual(xa, ya);
            }
            Object other = x instanceof PhpArray ? y : x;
            PhpArray arr = (PhpArray) (x instanceof PhpArray ? x : y);
            if (other == null || other instanceof Boolean) {
                return bool(arr) == bool(other);
            }
            return false;
        }
        if (x == null && y == null) {
            return true;
        }
        if (x instanceof Boolean || y instanceof Boolean) {
            return bool(x) == bool(y);
        }
        if (x == null || y == null) {
            Object other = x == null ? y : x;
            if (other instanceof String s) {
                return s.isEmpty();
            }
            return !bool(other);
        }
        if (x instanceof String xs && y instanceof String ys) {
            if (isNumeric(xs) && isNumeric(ys)) {
                return number(xs).compareTo(number(ys)) == 0;
            }
            return xs.equals(ys);
        }
        if (x instanceof String || y instanceof String) {
            String s = (String) (x instanceof String ? x : y);
            Object n = x instanceof String ? y : x;
            if (isNumeric(s)) {
                return number(s).compareTo(numberOf(n)) == 0;
            }
            return s.equals(PhpValues.text(toPhp(n)));
        }
        return numberOf(x).compareTo(numberOf(y)) == 0;
    }

    /** PHP 的布尔转换（{@code empty()} 的反面）。 */
    public static boolean bool(Object v) {
        Object x = normalize(v);
        return switch (x) {
            case null -> false;
            case Boolean b -> b;
            case Long l -> l != 0;
            case Double d -> d != 0.0;
            case String s -> !(s.isEmpty() || s.equals("0"));
            case PhpArray a -> a.size() > 0;
            default -> true;
        };
    }

    private static boolean arraysEqual(PhpArray a, PhpArray b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            PhpValue other = b.entries().get(e.getKey());
            if (other == null || !equals(e.getValue(), other)) {
                return false;
            }
        }
        return true;
    }

    /** 归一成 null、Boolean、Long、Double、String、PhpArray。 */
    private static Object normalize(Object v) {
        return switch (v) {
            case null -> null;
            case PhpValue.PhpNull n -> null;
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> f.value();
            case PhpValue.PhpString s -> s.value();
            case PhpArray a -> a;
            case Integer i -> (long) i;
            case Long l -> l;
            case Double d -> d;
            case Boolean b -> b;
            case String s -> s;
            default -> throw new IllegalArgumentException("不支持的类型");
        };
    }

    private static PhpValue toPhp(Object n) {
        return switch (n) {
            case Long l -> new PhpValue.PhpInt(l);
            case Double d -> new PhpValue.PhpFloat(d);
            default -> throw new IllegalArgumentException("不支持的类型");
        };
    }

    private static BigDecimal numberOf(Object n) {
        return switch (n) {
            case Long l -> BigDecimal.valueOf(l);
            case Double d -> new BigDecimal(d);
            case String s -> number(s);
            default -> throw new IllegalArgumentException("不支持的类型");
        };
    }

    private static BigDecimal number(String s) {
        String t = s.strip();
        if (t.startsWith("+")) {
            t = t.substring(1);
        }
        if (t.startsWith(".") || t.startsWith("-.")) {
            t = t.replaceFirst("\\.", "0.");
        }
        if (t.endsWith(".")) {
            t = t + "0";
        }
        t = t.replace(".e", ".0e").replace(".E", ".0E");
        return new BigDecimal(t);
    }
}
