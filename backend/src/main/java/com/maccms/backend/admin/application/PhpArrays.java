package com.maccms.backend.admin.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不可变 {@link PhpArray} 上的 PHP 写法（B15）：{@code $a[$k] = $v}、{@code unset($a[$k])}、{@code array_merge}。 */
public final class PhpArrays {

    private PhpArrays() {
    }

    public static PhpArray empty() {
        return new PhpArray(Map.of());
    }

    /** {@code $a[$k] = $v}：已有的键原位替换，新键追加在末尾（字符串键按 PHP 规则规范化）。 */
    public static PhpArray put(PhpArray a, Object key, PhpValue v) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>(a.entries());
        m.put(key instanceof String s ? PhpArray.normalizeKey(s) : key, v);
        return new PhpArray(m);
    }

    /** {@code $a[$k] = $v}，值为字符串。 */
    public static PhpArray put(PhpArray a, Object key, String v) {
        return put(a, key, new PhpValue.PhpString(v));
    }

    /** {@code $a[$k1][$k2] = $v}：中间层不是数组时换成空数组（PHP 对 null 与空串自动建数组）。 */
    public static PhpArray put(PhpArray a, Object k1, Object k2, PhpValue v) {
        PhpValue inner = a.entries().get(k1 instanceof String s ? PhpArray.normalizeKey(s) : k1);
        PhpArray arr = inner instanceof PhpArray x ? x : empty();
        return put(a, k1, put(arr, k2, v));
    }

    /** {@code unset($a[$k])}。 */
    public static PhpArray remove(PhpArray a, Object key) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>(a.entries());
        m.remove(key instanceof String s ? PhpArray.normalizeKey(s) : key);
        return new PhpArray(m);
    }

    /**
     * {@code array_merge($a, $b)}：字符串键后者覆盖前者（保留前者的位置），整数键重新编号后追加。
     */
    public static PhpArray merge(PhpArray a, PhpArray b) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        long next = 0;
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            if (e.getKey() instanceof Long) {
                m.put(next++, e.getValue());
            } else {
                m.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<Object, PhpValue> e : b.entries().entrySet()) {
            if (e.getKey() instanceof Long) {
                m.put(next++, e.getValue());
            } else {
                m.put(e.getKey(), e.getValue());
            }
        }
        return new PhpArray(m);
    }

    /** 数组（或 null/非数组时的空数组）。 */
    public static PhpArray arr(PhpValue v) {
        return v instanceof PhpArray a ? a : empty();
    }

    /** {@code $a[$k]}（不存在为 null）。 */
    public static PhpValue get(PhpValue a, Object key) {
        if (!(a instanceof PhpArray arr)) {
            return null;
        }
        return key instanceof String s ? arr.get(s) : arr.get((Long) key);
    }

    /** 0..n-1 的列表。 */
    public static PhpArray list(java.util.List<String> items) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        long i = 0;
        for (String s : items) {
            m.put(i++, new PhpValue.PhpString(s));
        }
        return new PhpArray(m);
    }
}
