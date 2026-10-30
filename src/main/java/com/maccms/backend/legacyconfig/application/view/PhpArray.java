package com.maccms.backend.legacyconfig.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PHP 有序数组，只读。键只有两种：{@link Long}（整数键）或 {@link String}（字符串键），顺序与 PHP 遍历顺序一致。
 * 字符串键按 PHP 规则规范化：十进制规范写法且在 64 位范围内的字符串键（如 {@code '1'}、{@code '-5'}）就是整数键，
 * {@code '01'}、{@code '-0'}、{@code ' 1'}、{@code '1.0'} 仍是字符串键（{@link #normalizeKey(String)}）。
 */
public final class PhpArray implements PhpValue {

    private static final Pattern CANONICAL_INT = Pattern.compile("0|-?[1-9][0-9]*");

    private final Map<Object, PhpValue> entries;

    /**
     * @param entries 键为 {@link Long} 或已规范化的 {@link String}，值非 null；按迭代顺序复制
     */
    public PhpArray(Map<Object, PhpValue> entries) {
        LinkedHashMap<Object, PhpValue> copy = new LinkedHashMap<>();
        entries.forEach((k, v) -> {
            if (!(k instanceof Long) && !(k instanceof String s && normalizeKey(s) instanceof String)) {
                throw new IllegalArgumentException("键只能是整数或非整数形式的字符串");
            }
            if (v == null) {
                throw new IllegalArgumentException("值不能为 null（PHP NULL 用 PhpNull.INSTANCE）");
            }
            copy.put(k, v);
        });
        this.entries = Collections.unmodifiableMap(copy);
    }

    /** 全部条目（只读，PHP 遍历顺序）。 */
    public Map<Object, PhpValue> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** 按 PHP 语义取值：{@code get("1")} 与 {@code get(1L)} 是同一个键。不存在时返回 null。 */
    public PhpValue get(String key) {
        return entries.get(normalizeKey(key));
    }

    public PhpValue get(long key) {
        return entries.get(key);
    }

    /** 逐层取值，如 {@code path("site", "site_name")}；中途不存在或不是数组时返回 null。 */
    public PhpValue path(String... keys) {
        PhpValue cur = this;
        for (String k : keys) {
            if (!(cur instanceof PhpArray a)) {
                return null;
            }
            cur = a.get(k);
        }
        return cur;
    }

    /** PHP 数组键规范化：十进制规范写法且不溢出的字符串转成 {@link Long}，其余保持字符串。 */
    public static Object normalizeKey(String key) {
        if (key.length() <= 20 && CANONICAL_INT.matcher(key).matches()) {
            try {
                return Long.parseLong(key);
            } catch (NumberFormatException e) {
                return key;
            }
        }
        return key;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpArray other && entries.equals(other.entries)
                && entries.keySet().stream().toList().equals(other.entries.keySet().stream().toList());
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        // 不输出内容：配置里可能有密钥
        return "PhpArray[size=" + entries.size() + "]";
    }
}
