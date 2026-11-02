package com.maccms.backend.phptemplate.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP 7.3 有序数组（技术设计 §6.5 RT-2）：键为 {@code int}（{@link Long}）或 {@code string}（{@link ZValue.Str}），
 * 按插入顺序遍历；覆盖已有键时保留原位置。
 *
 * <p>键规范化同 {@code legacyconfig.application.view.PhpArray.normalizeKey}（规范十进制整数串 → int），
 * 另按 PHP 7.3 处理非字符串键：{@code bool} → 0/1，{@code null} → {@code ""}，{@code float} → 截断取整
 * （同 {@code zend_dval_to_lval}）。</p>
 *
 * <p>可变：解释器负责 PHP 的写时复制语义，赋值/传参时用 {@link #copy()} 取快照（MC-4）。</p>
 */
public final class ZArray implements ZValue {

    private final LinkedHashMap<Object, ZValue> entries;
    /** 下一个自动下标（PHP {@code nNextFreeElement}）：初始 0，只增不减。 */
    private long nextFree;

    public ZArray() {
        this.entries = new LinkedHashMap<>();
        this.nextFree = 0;
    }

    private ZArray(LinkedHashMap<Object, ZValue> entries, long nextFree) {
        this.entries = entries;
        this.nextFree = nextFree;
    }

    /** 按顺序追加值的列表数组（{@code [v0, v1, …]}）。 */
    public static ZArray list(ZValue... values) {
        ZArray a = new ZArray();
        for (ZValue v : values) {
            a.append(v);
        }
        return a;
    }

    @Override
    public String typeName() {
        return "array";
    }

    /**
     * 把 PHP 下标值规范化成数组键：返回 {@link Long} 或 {@link ZValue.Str}；
     * 数组作下标是非法偏移（PHP 写 Warning、不读写），返回 {@code null}。
     */
    public static Object normalizeKey(ZValue key) {
        return switch (key) {
            case ZValue.Int i -> i.value();
            case ZValue.Str s -> normalizeStringKey(s);
            case ZValue.Bool b -> b.value() ? 1L : 0L;
            case ZValue.Null n -> ZValue.EMPTY_STRING;
            case ZValue.Float f -> PhpSemantics.doubleToLong(f.value());
            case ZArray a -> null;
        };
    }

    /** 字符串键：形如 {@code 0} 或 {@code -?[1-9][0-9]*} 且不溢出 64 位的转成 int，其余保持字符串（同 {@code PhpArray.normalizeKey}）。 */
    static Object normalizeStringKey(ZValue.Str s) {
        byte[] b = s.raw();
        int n = b.length;
        if (n == 0 || n > 20) {
            return s;
        }
        int i = 0;
        boolean neg = b[0] == '-';
        if (neg) {
            i = 1;
            if (n == 1) {
                return s;
            }
        }
        if (b[i] == '0' && (n - i > 1 || neg)) {
            return s; // 前导 0 与 "-0" 不是规范整数
        }
        long v = 0;
        for (int k = i; k < n; k++) {
            int d = b[k] - '0';
            if (d < 0 || d > 9) {
                return s;
            }
            // 按负数累加，-9223372036854775808 也能表示
            if (v < (Long.MIN_VALUE + d) / 10) {
                return s;
            }
            v = v * 10 - d;
        }
        if (!neg) {
            if (v == Long.MIN_VALUE) {
                return s;
            }
            v = -v;
        }
        return v;
    }

    /** 取值；键不存在或下标非法时返回 {@code null}（Java null，由调用方按 RT-5 转成 PHP null）。 */
    public ZValue get(ZValue key) {
        Object k = normalizeKey(key);
        return k == null ? null : entries.get(k);
    }

    public ZValue get(long key) {
        return entries.get(key);
    }

    public ZValue get(String key) {
        return get(ZValue.of(key));
    }

    public boolean containsKey(ZValue key) {
        Object k = normalizeKey(key);
        return k != null && entries.containsKey(k);
    }

    /** {@code $a[$key] = $value}；下标非法时不写入并返回 false。 */
    public boolean put(ZValue key, ZValue value) {
        Object k = normalizeKey(key);
        if (k == null) {
            return false;
        }
        putNormalized(k, value);
        return true;
    }

    public void put(String key, ZValue value) {
        put(ZValue.of(key), value);
    }

    public void put(long key, ZValue value) {
        putNormalized(key, value);
    }

    /** {@code $a[] = $value}：下标为当前最大整数键 + 1（至少 0）；下一下标已到 {@code PHP_INT_MAX} 时与 PHP 一样不写入并返回 false。 */
    public boolean append(ZValue value) {
        if (nextFree == Long.MAX_VALUE && entries.containsKey(Long.MAX_VALUE)) {
            return false;
        }
        putNormalized(nextFree, value);
        return true;
    }

    private void putNormalized(Object k, ZValue value) {
        if (value == null) {
            throw new IllegalArgumentException("值不能为 Java null（PHP null 用 ZValue.NULL）");
        }
        entries.put(k, value);
        if (k instanceof Long l && l >= nextFree) {
            nextFree = l == Long.MAX_VALUE ? Long.MAX_VALUE : l + 1;
        }
    }

    /** {@code unset($a[$key])}；不改变下一个自动下标。 */
    public void remove(ZValue key) {
        Object k = normalizeKey(key);
        if (k != null) {
            entries.remove(k);
        }
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 键（{@link Long} 或 {@link ZValue.Str}）按 PHP 遍历顺序，只读。 */
    public List<Object> keys() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    /** 键转成 PHP 值（{@link ZValue.Int} 或 {@link ZValue.Str}），供 {@code foreach ($a as $k => $v)}。 */
    public static ZValue keyValue(Object key) {
        return key instanceof Long l ? ZValue.of(l) : (ZValue.Str) key;
    }

    /** 全部条目，只读，按 PHP 遍历顺序。 */
    public Map<Object, ZValue> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** PHP 赋值语义的快照：复制本层条目与下一个自动下标，嵌套数组逐层复制，修改副本不影响原数组。 */
    public ZArray copy() {
        LinkedHashMap<Object, ZValue> c = new LinkedHashMap<>();
        entries.forEach((k, v) -> c.put(k, v instanceof ZArray a ? a.copy() : v));
        return new ZArray(c, nextFree);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ZArray other && entries.equals(other.entries)
                && new ArrayList<>(entries.keySet()).equals(new ArrayList<>(other.entries.keySet()));
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        // 不输出内容：模板变量里可能有用户数据
        return "ZArray[size=" + entries.size() + "]";
    }
}
