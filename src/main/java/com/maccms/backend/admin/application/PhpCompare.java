package com.maccms.backend.admin.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * PHP 8 的 {@code $a <=> $b}（排序用的标准比较，{@code SORT_REGULAR}；B15 播放器按 {@code sort} 重排）：null 或布尔参与时两边转成布尔；两个数字
 * 字符串（或数字与数字字符串）按数值；其余转成字符串后按字节比较（PHP 8 起整数与非数字字符串也按字符串比较）。数组只与数组比较元素个数。
 */
public final class PhpCompare {

    private PhpCompare() {
    }

    public static int compare(PhpValue a, PhpValue b) {
        PhpValue x = a == null ? PhpValue.PhpNull.INSTANCE : a;
        PhpValue y = b == null ? PhpValue.PhpNull.INSTANCE : b;
        if (x instanceof PhpArray xa && y instanceof PhpArray ya) {
            return compareArrays(xa, ya);
        }
        if (x instanceof PhpValue.PhpNull && y instanceof PhpValue.PhpString s) {
            return s.value().isEmpty() ? 0 : -1;
        }
        if (y instanceof PhpValue.PhpNull && x instanceof PhpValue.PhpString s) {
            return s.value().isEmpty() ? 0 : 1;
        }
        if (x instanceof PhpValue.PhpNull || y instanceof PhpValue.PhpNull || x instanceof PhpValue.PhpBool
                || y instanceof PhpValue.PhpBool) {
            return Boolean.compare(PhpLoose.bool(x), PhpLoose.bool(y));
        }
        if (x instanceof PhpArray) {
            return 1;
        }
        if (y instanceof PhpArray) {
            return -1;
        }
        boolean xn = !(x instanceof PhpValue.PhpString) || PhpLoose.isNumeric(PhpValues.text(x));
        boolean yn = !(y instanceof PhpValue.PhpString) || PhpLoose.isNumeric(PhpValues.text(y));
        if (xn && yn) {
            return number(x).compareTo(number(y));
        }
        int c = Arrays.compareUnsigned(PhpValues.text(x).getBytes(StandardCharsets.UTF_8),
                PhpValues.text(y).getBytes(StandardCharsets.UTF_8));
        return Integer.signum(c);
    }

    /**
     * PHP 数组的标准比较（{@code zend_hash_compare}，不按顺序）：元素个数不同按个数；否则逐个取左边的键，右边没有这个键时左边大，有则比较值。
     */
    static int compareArrays(PhpArray a, PhpArray b) {
        if (a.size() != b.size()) {
            return Integer.compare(a.size(), b.size());
        }
        for (java.util.Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            PhpValue other = b.entries().get(e.getKey());
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

    private static BigDecimal number(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpInt i -> BigDecimal.valueOf(i.value());
            case PhpValue.PhpFloat f -> new BigDecimal(f.value());
            default -> {
                String t = PhpValues.text(v).strip();
                if (t.startsWith("+")) {
                    t = t.substring(1);
                }
                if (t.startsWith(".") || t.startsWith("-.")) {
                    t = t.replaceFirst("\\.", "0.");
                }
                if (t.endsWith(".")) {
                    t = t + "0";
                }
                yield new BigDecimal(t.replace(".e", ".0e").replace(".E", ".0E"));
            }
        };
    }
}
