package com.maccms.backend.collect.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provide 文件解析结果里的 PHP 值（B18，26 文档第 3 节）。与旧 {@code json_decode($s, true)} 的结果同构：数组是
 * {@code LinkedHashMap<Object, Object>}（键为 {@link Long} 或 {@link String}，按 PHP 规则规范化，保持插入顺序），标量是
 * {@link String}、{@link Long}、{@link Double}、{@link Boolean} 与 {@code null}。这里放旧代码用到的几种 PHP 语义：字符串转换、
 * {@code empty()}、{@code $a['k']} 读取、与 0 的宽松比较。
 */
public final class ProvideValues {

    private static final MathContext PRECISION_14 = new MathContext(14, RoundingMode.HALF_EVEN);

    private ProvideValues() {
    }

    /** 新的 PHP 数组。 */
    public static Map<Object, Object> array() {
        return new LinkedHashMap<>();
    }

    /** PHP 数组键的规范化：规范十进制整数串（{@code "5"}、{@code "-5"}，不含 {@code "05"}、{@code "-0"}，在 int64 范围内）是整数键。 */
    public static Object key(String k) {
        Object n = PhpArray.normalizeKey(k);
        return n;
    }

    /** {@code $a['k']}：{@code a} 是数组时取键（没有时 null），否则 null（旧代码对标量取下标得到 null 并告警）。 */
    public static Object get(Object a, String k) {
        if (a instanceof Map<?, ?> m) {
            return m.get(key(k));
        }
        return null;
    }

    /** PHP 的字符串转换（{@code (string)$v}、字符串拼接、{@code implode}）；浮点按 {@code precision=14}。数组为 {@code "Array"}。 */
    public static String str(Object v) {
        return switch (v) {
            case null -> "";
            case String s -> s;
            case Long l -> Long.toString(l);
            case Double d -> phpDouble(d);
            case Boolean b -> b ? "1" : "";
            case Map<?, ?> m -> "Array";
            default -> throw new IllegalArgumentException("不是 PHP 值：" + v.getClass().getName());
        };
    }

    /** PHP {@code empty($v)}。 */
    public static boolean empty(Object v) {
        return switch (v) {
            case null -> true;
            case String s -> s.isEmpty() || s.equals("0");
            case Long l -> l == 0;
            case Double d -> d == 0.0;
            case Boolean b -> !b;
            case Map<?, ?> m -> m.isEmpty();
            default -> false;
        };
    }

    /**
     * PHP 8 {@code $v == 0}：数字按数值比较；null、false 为真；数字串按数值比较，其他字符串为假（PHP 7 把非数字串当 0，这里的值是
     * {@code bind.php} 里 {@code intval} 写入的整数或解析出的 0，取不到字符串）；数组为假。
     */
    public static boolean looseEqualsZero(Object v) {
        return switch (v) {
            case null -> true;
            case Long l -> l == 0;
            case Double d -> d == 0.0;
            case Boolean b -> !b;
            case String s -> isNumeric(s) && Double.parseDouble(s.strip()) == 0.0;
            default -> false;
        };
    }

    /** PHP {@code $v > 0}（同上的口径）。 */
    public static boolean greaterThanZero(Object v) {
        return switch (v) {
            case null -> false;
            case Long l -> l > 0;
            case Double d -> d > 0.0;
            case Boolean b -> b;
            case String s -> isNumeric(s) ? Double.parseDouble(s.strip()) > 0.0 : s.compareTo("0") > 0;
            case Map<?, ?> m -> true;
            default -> false;
        };
    }

    /** 旧配置值（{@code bind.php} 里的值）换成本类的表示。 */
    public static Object of(PhpValue v) {
        return switch (v) {
            case null -> null;
            case PhpValue.PhpString s -> s.value();
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> f.value();
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpNull n -> null;
            case PhpArray a -> {
                Map<Object, Object> m = array();
                a.entries().forEach((k, x) -> m.put(k, of(x)));
                yield m;
            }
        };
    }

    /** PHP 数字串（前后可有空白）的数值；不是数字串时 null。 */
    static Double numeric(String s) {
        return isNumeric(s) ? Double.parseDouble(s.strip()) : null;
    }

    private static boolean isNumeric(String s) {
        String t = s.strip();
        return !t.isEmpty() && t.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    }

    /**
     * PHP 浮点转字符串（{@code precision=14}，{@code %.14G}）：14 位有效数字（按精确二进制值舍入到最近、平局取偶），去掉末尾的 0；十进制指数
     * 小于 -4 或不小于 14 时写成 {@code 1.0E+20}、{@code 1.5E-7}（尾数只有一位时补 {@code .0}）。
     */
    static String phpDouble(double d) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        if (d == 0.0) {
            return (1 / d < 0) ? "-0" : "0";
        }
        BigDecimal r = new BigDecimal(d).round(PRECISION_14).stripTrailingZeros();
        int exp = r.precision() - r.scale() - 1;
        if (exp < -4 || exp >= 14) {
            String digits = r.unscaledValue().abs().toString();
            StringBuilder sb = new StringBuilder();
            if (r.signum() < 0) {
                sb.append('-');
            }
            sb.append(digits.charAt(0)).append('.');
            sb.append(digits.length() > 1 ? digits.substring(1) : "0");
            sb.append('E').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
            return sb.toString();
        }
        return r.toPlainString();
    }
}
