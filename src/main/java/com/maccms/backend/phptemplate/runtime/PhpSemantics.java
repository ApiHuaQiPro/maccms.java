package com.maccms.backend.phptemplate.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * PHP 7.3 值语义（技术设计 §6.5 RT-2～RT-5），逐条按 Zend 引擎 7.3 的行为移植，结果以 C-33 ② 的黄金向量为准（V-11）：
 * <ul>
 *   <li>字符串化（RT-3）：{@code (string)}/{@code echo}，浮点按 {@code precision=14} 的 {@code %.14G}（{@code php_gcvt}）；</li>
 *   <li>比较（RT-4）：{@code compare_function} 的弱比较、{@code ===}、{@code in_array}；</li>
 *   <li>未定义取值与下标（RT-5）：取不到时为 {@code null}，字符串下标取单字节；</li>
 *   <li>真值、数值字符串（{@code is_numeric_string_ex}）、转 int（{@code zval_get_long}）等比较所需的转换。</li>
 * </ul>
 * 只依赖 JDK；不写日志（PHP 的 Notice/Warning 只进日志、不影响响应体，由解释器决定是否记录）。
 */
public final class PhpSemantics {

    /** {@code php.ini} 的 {@code precision}（PHPB-7）。 */
    public static final int PRECISION = 14;

    private static final MathContext PRECISION_CONTEXT = new MathContext(PRECISION, RoundingMode.HALF_EVEN);
    private static final byte[] ARRAY = "Array".getBytes(StandardCharsets.US_ASCII);
    /** 19 位十进制且不小于它即超出 64 位（Zend {@code long_min_digits}）。 */
    private static final byte[] LONG_MIN_DIGITS = "9223372036854775808".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_LENGTH_OF_LONG = 20;

    private PhpSemantics() {
    }

    // ------------------------------------------------------------------ RT-3 字符串化

    /** {@code (string)$v}：true → "1"，false/null → ""，int 十进制，float 按 {@link #formatDouble}，数组 → "Array"。 */
    public static ZValue.Str toStr(ZValue v) {
        return switch (v) {
            case ZValue.Str s -> s;
            case ZValue.Null n -> ZValue.EMPTY_STRING;
            case ZValue.Bool b -> b.value() ? ZValue.of("1") : ZValue.EMPTY_STRING;
            case ZValue.Int i -> ZValue.of(Long.toString(i.value()));
            case ZValue.Float f -> ZValue.of(formatDouble(f.value()));
            case ZArray a -> ZValue.bytes(ARRAY);
        };
    }

    /** {@link #toStr} 的字节形式（echo 写入输出缓冲用）。 */
    public static byte[] toBytes(ZValue v) {
        return toStr(v).raw().clone();
    }

    /**
     * 浮点转字符串，等同 PHP 7.3 {@code zend_strpprintf("%.*G", precision=14, d)}：
     * 正确舍入到 14 位有效数字并去掉末尾 0；十进制指数 &lt; -4 或 ≥ 15 时用 {@code 1.0E+14} 形式；
     * {@code -0.0} → {@code "-0"}；无穷与 NaN → {@code INF}/{@code -INF}/{@code NAN}。
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        boolean negative = (Double.doubleToRawLongBits(d) & Long.MIN_VALUE) != 0;
        String digits;
        int decpt;
        if (d == 0) {
            digits = "0";
            decpt = 1;
        } else {
            // zend_dtoa(value, mode=2, ndigit=14)：按精确二进制值舍入（同 dtoa 的偶数舍入），去掉末尾 0
            BigDecimal r = new BigDecimal(Math.abs(d)).round(PRECISION_CONTEXT).stripTrailingZeros();
            digits = r.unscaledValue().toString();
            decpt = digits.length() - r.scale();
        }
        StringBuilder sb = new StringBuilder(24);
        if (negative) {
            sb.append('-');
        }
        // php_gcvt
        if (decpt < 0 ? decpt < -3 : decpt > PRECISION) {
            int exp = decpt - 1;
            sb.append(digits.charAt(0)).append('.');
            sb.append(digits.length() == 1 ? "0" : digits.substring(1));
            sb.append('E').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
        } else if (decpt < 0) {
            sb.append("0.");
            sb.append("0".repeat(-decpt));
            sb.append(digits);
        } else {
            for (int i = 0; i < decpt; i++) {
                sb.append(i < digits.length() ? digits.charAt(i) : '0');
            }
            if (digits.length() > decpt) {
                if (decpt == 0) {
                    sb.append('0');
                }
                sb.append('.').append(digits, decpt, digits.length());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 真值与转换

    /** PHP 真值（{@code zend_is_true}）：""、"0"、0、0.0、空数组、null、false 为假；NaN 为真。 */
    public static boolean isTrue(ZValue v) {
        return switch (v) {
            case ZValue.Null n -> false;
            case ZValue.Bool b -> b.value();
            case ZValue.Int i -> i.value() != 0;
            case ZValue.Float f -> f.value() != 0 || Double.isNaN(f.value());
            case ZValue.Str s -> !(s.length() == 0 || (s.length() == 1 && s.byteAt(0) == '0'));
            case ZArray a -> !a.isEmpty();
        };
    }

    /** {@code isset}：值存在且不为 null（取不到的值传 Java null 或 {@link ZValue#NULL}）。 */
    public static boolean isset(ZValue v) {
        return v != null && !(v instanceof ZValue.Null);
    }

    /** {@code empty}：取不到或真值为假。 */
    public static boolean empty(ZValue v) {
        return v == null || !isTrue(v);
    }

    /** 数值字符串解析结果（{@code is_numeric_string_ex}）。 */
    public record Numeric(boolean isDouble, long longValue, double doubleValue, int overflow, boolean wellFormed) {
        public ZValue value() {
            return isDouble ? ZValue.of(doubleValue) : ZValue.of(longValue);
        }
    }

    /**
     * PHP 7.3 {@code is_numeric_string_ex}：跳过前导空白（空格、\t、\n、\r、\v、\f），可带正负号，
     * 十进制整数/小数/指数（无十六进制）。返回 {@code null} 表示没有数字前缀；
     * {@code wellFormed=false} 表示数字后面还有其他字符（含尾随空白，7.3 不允许），调用方按 {@code allow_errors} 决定是否接受。
     * 整数超出 64 位时转为浮点并记 {@code overflow}（+1/-1）。
     */
    public static Numeric parseNumeric(ZValue.Str s) {
        byte[] b = s.raw();
        int n = b.length;
        int p = 0;
        while (p < n && (b[p] == ' ' || b[p] == '\t' || b[p] == '\n' || b[p] == '\r' || b[p] == 0x0b || b[p] == '\f')) {
            p++;
        }
        int start = p; // Zend 的 str（跳过空白后）
        boolean neg = false;
        if (p < n && (b[p] == '-' || b[p] == '+')) {
            neg = b[p] == '-';
            p++;
        }
        boolean isDouble;
        int overflow = 0;
        int end;
        if (p < n && isDigit(b[p])) {
            int q = p;
            while (q < n && b[q] == '0') {
                q++;
            }
            int digitsStart = q;
            while (q < n && isDigit(b[q])) {
                q++;
            }
            int digits = q - digitsStart;
            end = scanFractionAndExponent(b, q);
            if (digits >= MAX_LENGTH_OF_LONG) {
                // Zend 数到 20 位就按溢出转浮点，之后的小数/指数仍由 zend_strtod 一并解析
                isDouble = true;
                overflow = neg ? -1 : 1;
            } else if (end > q) {
                isDouble = true;
            } else if (digits == MAX_LENGTH_OF_LONG - 1
                    && !(compareDigits(b, digitsStart) < 0 || (compareDigits(b, digitsStart) == 0 && neg))) {
                isDouble = true;
                overflow = neg ? -1 : 1;
            } else {
                isDouble = false;
            }
            if (!isDouble) {
                long v = 0;
                for (int k = digitsStart; k < end; k++) {
                    v = v * 10 + (b[k] - '0');
                }
                return new Numeric(false, neg ? -v : v, 0, 0, end == n);
            }
        } else if (p + 1 < n && b[p] == '.' && isDigit(b[p + 1])) {
            isDouble = true;
            end = scanFractionAndExponent(b, p);
        } else {
            return null;
        }
        double dv = Double.parseDouble(new String(b, start, end - start, StandardCharsets.US_ASCII));
        return new Numeric(true, 0, dv, overflow, end == n);
    }

    /** 从 {@code q}（整数部分之后）起识别 {@code .数字*} 与 {@code [eE][+-]?数字+}，返回数字结束位置；没有则返回 q。 */
    private static int scanFractionAndExponent(byte[] b, int q) {
        int n = b.length;
        int r = q;
        if (r < n && b[r] == '.') {
            // 整数部分之后的 "." 总是小数点（"1." 也是浮点）；没有整数部分时已由调用方保证后跟数字
            r++;
            while (r < n && isDigit(b[r])) {
                r++;
            }
        }
        if (r < n && (b[r] == 'e' || b[r] == 'E')) {
            int e = r + 1;
            if (e < n && (b[e] == '-' || b[e] == '+')) {
                e++;
            }
            if (e < n && isDigit(b[e])) {
                while (e < n && isDigit(b[e])) {
                    e++;
                }
                r = e;
            }
        }
        return r;
    }

    private static int compareDigits(byte[] b, int from) {
        for (int i = 0; i < LONG_MIN_DIGITS.length; i++) {
            int c = Integer.compare(b[from + i], LONG_MIN_DIGITS[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    /** 比较时的标量转数值（{@code zendi_convert_scalar_to_number}，静默）：字符串取数字前缀，无前缀为 0。 */
    static ZValue toNumberForCompare(ZValue v) {
        return switch (v) {
            case ZValue.Int i -> i;
            case ZValue.Float f -> f;
            case ZValue.Null n -> ZValue.of(0L);
            case ZValue.Bool b -> ZValue.of(b.value() ? 1L : 0L);
            case ZValue.Str s -> {
                Numeric num = parseNumeric(s);
                yield num == null ? ZValue.of(0L) : num.value();
            }
            case ZArray a -> a;
        };
    }

    /**
     * {@code zval_get_long}：字符串取数字前缀（浮点超范围截到 {@code PHP_INT_MAX}/{@code PHP_INT_MIN}），
     * 浮点按 {@link #doubleToLong}，数组非空为 1。
     */
    public static long toLong(ZValue v) {
        return switch (v) {
            case ZValue.Null n -> 0L;
            case ZValue.Bool b -> b.value() ? 1L : 0L;
            case ZValue.Int i -> i.value();
            case ZValue.Float f -> doubleToLong(f.value());
            case ZValue.Str s -> {
                Numeric num = parseNumeric(s);
                if (num == null) {
                    yield 0L;
                }
                yield num.isDouble() ? doubleToLongCap(num.doubleValue()) : num.longValue();
            }
            case ZArray a -> a.isEmpty() ? 0L : 1L;
        };
    }

    /** {@code zend_dval_to_lval}（PHP 7.3，64 位）：非有限值为 0，超出范围按 2^64 取模。 */
    public static long doubleToLong(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0L;
        }
        if (d >= -0x1p63 && d < 0x1p63) {
            return (long) d;
        }
        // 这么大的 double 都是整数；取低 64 位即按 2^64 取模
        return new BigDecimal(d).toBigInteger().longValue();
    }

    /** {@code zend_dval_to_lval_cap}：非有限值为 0，超出范围截到最大/最小值。 */
    static long doubleToLongCap(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0L;
        }
        return (long) d; // Java 的窄化转换即截断到 Long.MIN_VALUE/MAX_VALUE
    }

    // ------------------------------------------------------------------ RT-4 比较

    /** {@code $a == $b}（PHP 7.3 弱比较）。 */
    public static boolean looseEquals(ZValue a, ZValue b) {
        return compare(a, b) == 0;
    }

    /** {@code $a < $b}；{@code $a > $b} 即 {@code isSmaller(b, a)}（与 Zend 交换操作数的做法一致）。 */
    public static boolean isSmaller(ZValue a, ZValue b) {
        return compare(a, b) < 0;
    }

    /** {@code $a <= $b}；{@code $a >= $b} 即 {@code isSmallerOrEqual(b, a)}。 */
    public static boolean isSmallerOrEqual(ZValue a, ZValue b) {
        return compare(a, b) <= 0;
    }

    /**
     * PHP 7.3 {@code compare_function}：返回负数、0 或正数。
     * “不可比较”统一返回 1（所以 {@code ==}、{@code <}、{@code <=} 及交换操作数后的 {@code >}、{@code >=} 都为假）：
     * 数组之间键不对应；数值比较（含字符串、数组元素转成数值后）任一侧是 NaN（S306-1）。
     */
    public static int compare(ZValue a, ZValue b) {
        if (isNumber(a) && isNumber(b)) {
            return numericCompare(a, b);
        }
        if (a instanceof ZValue.Str x && b instanceof ZValue.Str y) {
            return smartStrcmp(x, y);
        }
        if (a instanceof ZArray x && b instanceof ZArray y) {
            return compareArrays(x, y);
        }
        if (a instanceof ZValue.Null && b instanceof ZValue.Str y) {
            return Integer.signum(binaryStrcmp(ZValue.EMPTY_STRING, y));
        }
        if (a instanceof ZValue.Str x && b instanceof ZValue.Null) {
            return Integer.signum(binaryStrcmp(x, ZValue.EMPTY_STRING));
        }
        // null/false 与 true 按布尔比较
        if (isNullOrFalse(a)) {
            return isTrue(b) ? -1 : 0;
        }
        if (a instanceof ZValue.Bool) {
            return isTrue(b) ? 0 : 1;
        }
        if (isNullOrFalse(b)) {
            return isTrue(a) ? 1 : 0;
        }
        if (b instanceof ZValue.Bool) {
            return isTrue(a) ? 0 : -1;
        }
        if (a instanceof ZArray) {
            return 1;
        }
        if (b instanceof ZArray) {
            return -1;
        }
        // 余下为数值与字符串混合：字符串取数字前缀后按数值比较
        return compare(toNumberForCompare(a), toNumberForCompare(b));
    }

    /** {@code $a === $b}：类型相同且值相同；数组要求键与顺序相同、值逐个全等。 */
    public static boolean identical(ZValue a, ZValue b) {
        return switch (a) {
            case ZValue.Null n -> b instanceof ZValue.Null;
            case ZValue.Bool x -> b instanceof ZValue.Bool y && x.value() == y.value();
            case ZValue.Int x -> b instanceof ZValue.Int y && x.value() == y.value();
            case ZValue.Float x -> b instanceof ZValue.Float y && x.value() == y.value();
            case ZValue.Str x -> b instanceof ZValue.Str y && x.equals(y);
            case ZArray x -> b instanceof ZArray y && identicalArrays(x, y);
        };
    }

    /** {@code in_array($needle, $haystack, $strict)}：默认弱比较（RT-4）。 */
    public static boolean inArray(ZValue needle, ZArray haystack, boolean strict) {
        for (ZValue v : haystack.entries().values()) {
            if (strict ? identical(v, needle) : looseEquals(v, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumber(ZValue v) {
        return v instanceof ZValue.Int || v instanceof ZValue.Float;
    }

    private static boolean isNullOrFalse(ZValue v) {
        return v instanceof ZValue.Null || (v instanceof ZValue.Bool b && !b.value());
    }

    private static double toDouble(ZValue v) {
        return v instanceof ZValue.Int i ? (double) i.value() : ((ZValue.Float) v).value();
    }

    /** int/float 之间：两边都是 int 按整数比较，否则转 double 比较；有 NaN 时不可比较，两个方向都返回 1。 */
    private static int numericCompare(ZValue a, ZValue b) {
        if (a instanceof ZValue.Int x && b instanceof ZValue.Int y) {
            return Long.compare(x.value(), y.value());
        }
        double x = toDouble(a);
        double y = toDouble(b);
        return x == y ? 0 : (x < y ? -1 : 1);
    }

    /** {@code zendi_smart_strcmp}：两边都是完整数值串时按数值比较（含溢出的特殊处理），否则按字节比较。 */
    static int smartStrcmp(ZValue.Str s1, ZValue.Str s2) {
        Numeric n1 = parseNumeric(s1);
        Numeric n2 = n1 != null && n1.wellFormed() ? parseNumeric(s2) : null;
        if (n1 == null || !n1.wellFormed() || n2 == null || !n2.wellFormed()) {
            return Integer.signum(binaryStrcmp(s1, s2));
        }
        if (n1.overflow() != 0 && n1.overflow() == n2.overflow() && n1.doubleValue() - n2.doubleValue() == 0.0) {
            return Integer.signum(binaryStrcmp(s1, s2));
        }
        if (n1.isDouble() || n2.isDouble()) {
            double d1;
            double d2;
            if (!n1.isDouble()) {
                if (n2.overflow() != 0) {
                    return -n2.overflow();
                }
                d1 = n1.longValue();
                d2 = n2.doubleValue();
            } else if (!n2.isDouble()) {
                if (n1.overflow() != 0) {
                    return n1.overflow();
                }
                d1 = n1.doubleValue();
                d2 = n2.longValue();
            } else {
                d1 = n1.doubleValue();
                d2 = n2.doubleValue();
                if (d1 == d2 && Double.isInfinite(d1)) {
                    return Integer.signum(binaryStrcmp(s1, s2));
                }
            }
            double diff = d1 - d2;
            return diff > 0 ? 1 : (diff < 0 ? -1 : 0);
        }
        return Long.compare(n1.longValue(), n2.longValue());
    }

    /** {@code zend_binary_strcmp}：无符号逐字节比较，前缀相同时短的小。 */
    static int binaryStrcmp(ZValue.Str s1, ZValue.Str s2) {
        return Arrays.compareUnsigned(s1.raw(), s2.raw());
    }

    /** {@code zend_compare_arrays}（无序）：先比个数；再按左侧键序在右侧找同名键逐个比较，缺键为“不可比较”（1）。 */
    private static int compareArrays(ZArray a, ZArray b) {
        if (a == b) {
            return 0;
        }
        if (a.size() != b.size()) {
            return a.size() > b.size() ? 1 : -1;
        }
        for (Map.Entry<Object, ZValue> e : a.entries().entrySet()) {
            ZValue other = b.entries().get(e.getKey());
            if (other == null) {
                return 1;
            }
            int c = compare(e.getValue(), other);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static boolean identicalArrays(ZArray a, ZArray b) {
        if (a == b) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        Iterator<Map.Entry<Object, ZValue>> ib = b.entries().entrySet().iterator();
        for (Map.Entry<Object, ZValue> ea : a.entries().entrySet()) {
            Map.Entry<Object, ZValue> eb = ib.next();
            if (!ea.getKey().equals(eb.getKey()) || !identical(ea.getValue(), eb.getValue())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ RT-5 未定义取值与下标

    /**
     * 读下标 {@code $container[$dim]}（输出/运算上下文，PHP {@code BP_VAR_R}）：
     * 数组取不到为 null；字符串取单字节，越界为空串；null、标量容器为 null（7.3 不报错）。
     */
    public static ZValue fetchDim(ZValue container, ZValue dim) {
        return fetchDim(container, dim, false);
    }

    /**
     * 读下标（{@code isset}/{@code empty} 上下文，PHP {@code BP_VAR_IS}）：与 {@link #fetchDim} 相同，
     * 只是字符串越界或下标不是整数串时为 null。
     */
    public static ZValue fetchDimQuiet(ZValue container, ZValue dim) {
        return fetchDim(container, dim, true);
    }

    private static ZValue fetchDim(ZValue container, ZValue dim, boolean quiet) {
        if (container instanceof ZArray a) {
            ZValue v = a.get(dim);
            return v == null ? ZValue.NULL : v;
        }
        if (container instanceof ZValue.Str s) {
            return stringOffset(s, dim, quiet);
        }
        return ZValue.NULL;
    }

    /** 字符串下标：按字节取，负数从末尾数；整数串（如 {@code "1"}）按整数，其他下标先转 int（PHP 写 Warning/Notice）。 */
    private static ZValue stringOffset(ZValue.Str s, ZValue dim, boolean quiet) {
        long offset;
        if (dim instanceof ZValue.Int i) {
            offset = i.value();
        } else {
            if (quiet && dim instanceof ZArray) {
                return ZValue.NULL;
            }
            if (quiet && dim instanceof ZValue.Str ds) {
                Numeric num = parseNumeric(ds);
                if (num == null || num.isDouble() || !num.wellFormed()) {
                    return ZValue.NULL;
                }
            }
            offset = toLong(dim);
        }
        long len = s.length();
        if (offset < 0 ? offset < -len : offset >= len) {
            return quiet ? ZValue.NULL : ZValue.EMPTY_STRING;
        }
        int real = (int) (offset < 0 ? len + offset : offset);
        return ZValue.bytes(new byte[] {(byte) s.byteAt(real)});
    }
}
