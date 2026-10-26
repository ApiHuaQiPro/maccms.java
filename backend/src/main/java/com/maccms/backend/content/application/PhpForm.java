package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧控制器里的 {@code $param = input()} / {@code input('post.')}（B13，22 文档第 4.1 节）：按出现顺序的参数表，值是字节串（PHP 字符串）或数组
 * （{@code 名[]}、{@code 名[键]}）。后台 web 层按 ThinkPHP 的规则解析出来交给内容模块；内容模块在副本上按旧模型的步骤增删改
 * （{@code $data['x'] = …}、{@code unset($data['x'])}），最后只把表中有的列写库（{@link AdminRowValues}）。
 */
public final class PhpForm {

    /**
     * 数组参数：各项按出现顺序；{@code keys} 与 {@code values} 一一对应，{@code 名[]} 的键为 null（PHP 自动编号）。
     */
    public record Arr(List<String> keys, List<byte[]> values) {

        public Arr {
            keys = Collections.unmodifiableList(new ArrayList<>(keys));
            values = List.copyOf(values);
            if (keys.size() != values.size()) {
                throw new IllegalArgumentException("键与值的个数不同");
            }
        }

        public static Arr of(List<byte[]> values) {
            List<String> k = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                k.add(null);
            }
            return new Arr(k, values);
        }

        public int size() {
            return values.size();
        }

        public byte[] get(int i) {
            return values.get(i);
        }

        /**
         * PHP 数组的实际键（{@code []} 自动编号：从 0 起，取已有最大整数键 + 1）；同一个键出现几次时只留最后一次的值、位置不变。
         */
        public LinkedHashMap<String, byte[]> entries() {
            LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
            long next = 0;
            for (int i = 0; i < values.size(); i++) {
                String k = keys.get(i);
                if (k == null) {
                    k = Long.toString(next);
                }
                Long n = intKey(k);
                if (n != null && n >= next) {
                    next = n + 1;
                }
                out.put(n != null ? Long.toString(n) : k, values.get(i));
            }
            return out;
        }

        /** PHP 的整数键：十进制、无前导零（"0" 除外）、可带负号。 */
        static Long intKey(String k) {
            if (k.isEmpty() || k.length() > 18) {
                return null;
            }
            int i = k.charAt(0) == '-' ? 1 : 0;
            if (i == k.length() || (k.charAt(i) == '0' && k.length() > i + 1) || (i == 1 && k.equals("-0"))) {
                return null;
            }
            for (int j = i; j < k.length(); j++) {
                if (k.charAt(j) < '0' || k.charAt(j) > '9') {
                    return null;
                }
            }
            return Long.parseLong(k);
        }
    }

    /** PHP 的 null（取不存在的数组项得到的值）：{@code empty()} 为真、{@code require} 不通过，写库时是 SQL NULL。 */
    private static final Object NULL = new Object();

    private final LinkedHashMap<String, Object> data;

    private PhpForm(LinkedHashMap<String, Object> data) {
        this.data = data;
    }

    public static PhpForm empty() {
        return new PhpForm(new LinkedHashMap<>());
    }

    /** 副本（各值本身不可变，可以共享）。 */
    public PhpForm copy() {
        return new PhpForm(new LinkedHashMap<>(data));
    }

    public PhpForm put(String name, byte[] value) {
        data.put(name, value.clone());
        return this;
    }

    public PhpForm put(String name, String value) {
        data.put(name, value.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** {@code $data[name] = null}。 */
    public PhpForm putNull(String name) {
        data.put(name, NULL);
        return this;
    }

    /** 值是 PHP 的 null。 */
    public boolean isNull(String name) {
        return data.get(name) == NULL;
    }

    public PhpForm putArray(String name, Arr value) {
        data.put(name, value);
        return this;
    }

    public void remove(String name) {
        data.remove(name);
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public boolean isArray(String name) {
        return data.get(name) instanceof Arr;
    }

    /** 标量的字节（副本）；缺失或是数组时为 null。 */
    public byte[] scalar(String name) {
        Object v = data.get(name);
        return v instanceof byte[] b ? b.clone() : null;
    }

    /** 标量按 UTF-8 解码的文字；缺失或是数组时为 null（不检查是否合法 UTF-8）。 */
    public String text(String name) {
        byte[] b = scalar(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public Arr array(String name) {
        Object v = data.get(name);
        return v instanceof Arr a ? a : null;
    }

    /** PHP {@code empty($data[name])}：缺失、空串、{@code "0"}、空数组。 */
    public boolean empty(String name) {
        Object v = data.get(name);
        if (v == null || v == NULL) {
            return true;
        }
        if (v instanceof Arr a) {
            return a.size() == 0;
        }
        return phpEmpty((byte[]) v);
    }

    /** ThinkPHP 验证规则 {@code require}（{@code !empty($v) || '0' == $v}）：缺失、空串、空数组不通过，{@code "0"} 通过。 */
    public boolean require(String name) {
        Object v = data.get(name);
        if (v == null || v == NULL) {
            return false;
        }
        if (v instanceof Arr a) {
            return a.size() > 0;
        }
        return ((byte[]) v).length > 0;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(data.keySet());
    }

    /** 全部参数（按出现顺序，只读）。 */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(data);
    }

    /** PHP {@code empty($s)} 对字符串：空串或 {@code "0"}。 */
    public static boolean phpEmpty(byte[] v) {
        return v == null || v.length == 0 || (v.length == 1 && v[0] == '0');
    }
}
