package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.common.web.PhpCookies;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台请求参数（21 文档第 4.1 节）：按旧 PHP 填充 {@code $_GET}/{@code $_POST} 的规则<b>按字节</b>解析查询串与 urlencoded 正文，保留
 * {@code 名[]} 数组的各项（权限复选框 {@code admin_auth[]}、批量 {@code ids[]}），每个值（含数组元素）再经 ThinkPHP {@code filterExp}
 * （{@link LegacyInput#filterExp}）。
 *
 * <ul>
 * <li>按 {@code &} 分段，空段忽略；第一个 {@code =} 前为名、后为值；名与值各 {@code urldecode} 一次；名前的空格去掉，{@code [} 之前的
 * 空格与 {@code .} 变 {@code _}；名为空的段忽略。</li>
 * <li>{@code 名[...]}（有配对的 {@code ]}）是数组：各项按出现顺序追加（新版不区分 {@code []} 与 {@code [键]}，后台只用到前者）；标量与数组同名时
 * 后到的覆盖先到的（与 PHP 相同）。</li>
 * </ul>
 * {@code input()} 的合并（ThinkPHP 5.0 {@code Request::param}）：GET 请求为 GET + 路径参数；POST 请求为 GET + POST + 路径参数，后者覆盖前者。
 */
public final class AdminParams {

    /** 一个参数：标量（{@code items} 为 null）或数组。 */
    public record Value(byte[] scalar, List<byte[]> items) {

        public boolean array() {
            return items != null;
        }
    }

    private final Map<String, Value> values;

    private AdminParams(Map<String, Value> values) {
        this.values = values;
    }

    public static AdminParams empty() {
        return new AdminParams(Map.of());
    }

    /** 按字节解析一段查询串或 urlencoded 正文。 */
    public static AdminParams parse(byte[] raw) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (raw != null) {
            int start = 0;
            for (int i = 0; i <= raw.length; i++) {
                if (i == raw.length || raw[i] == '&') {
                    if (i > start) {
                        segment(out, Arrays.copyOfRange(raw, start, i));
                    }
                    start = i + 1;
                }
            }
        }
        return new AdminParams(Collections.unmodifiableMap(out));
    }

    /** 查询串（Servlet 给出的原始字符串，未解码）。 */
    public static AdminParams parseQuery(String queryString) {
        return queryString == null ? empty() : parse(queryString.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** {@code array_merge($a, $b, $route)}：后者覆盖前者，键保持首次出现的位置。 */
    public static AdminParams merge(AdminParams a, AdminParams b, Map<String, String> route) {
        Map<String, Value> out = new LinkedHashMap<>(a.values);
        out.putAll(b.values);
        for (Map.Entry<String, String> e : route.entrySet()) {
            out.put(e.getKey(), new Value(LegacyInput.filterExp(e.getValue().getBytes(StandardCharsets.UTF_8)), null));
        }
        return new AdminParams(Collections.unmodifiableMap(out));
    }

    /** 标量的字节；缺失或是数组时为 null。 */
    public byte[] scalar(String name) {
        Value v = values.get(name);
        return v == null || v.array() ? null : v.scalar();
    }

    /** 数组的各项；缺失或是标量时为 null。 */
    public List<byte[]> list(String name) {
        Value v = values.get(name);
        return v == null || !v.array() ? null : v.items();
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** 全部参数（按出现顺序）。 */
    public Map<String, Value> values() {
        return values;
    }

    private static void segment(Map<String, Value> out, byte[] seg) {
        int eq = -1;
        for (int i = 0; i < seg.length; i++) {
            if (seg[i] == '=') {
                eq = i;
                break;
            }
        }
        byte[] name = PhpCookies.urlDecode(Arrays.copyOfRange(seg, 0, eq < 0 ? seg.length : eq));
        byte[] value = eq < 0 ? new byte[0] : PhpCookies.urlDecode(Arrays.copyOfRange(seg, eq + 1, seg.length));
        String n = new String(name, StandardCharsets.ISO_8859_1);
        int i = 0;
        while (i < n.length() && n.charAt(i) == ' ') {
            i++;
        }
        n = n.substring(i);
        int bracket = n.indexOf('[');
        boolean array = false;
        String base = n;
        if (bracket >= 0) {
            if (n.indexOf(']', bracket) >= 0) {
                array = true;
                base = n.substring(0, bracket);
            } else {
                base = n.substring(0, bracket) + "_" + n.substring(bracket + 1);
            }
        }
        if (base.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(base.length());
        for (int k = 0; k < base.length(); k++) {
            char c = base.charAt(k);
            sb.append(c == ' ' || c == '.' ? '_' : c);
        }
        String key = new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        byte[] filtered = LegacyInput.filterExp(value);
        if (array) {
            Value prev = out.get(key);
            List<byte[]> items = prev != null && prev.array() ? new ArrayList<>(prev.items()) : new ArrayList<>();
            items.add(filtered);
            // 同名覆盖时 PHP 数组保留键原来的位置（LinkedHashMap.put 同样不移动已有的键）
            out.put(key, new Value(null, Collections.unmodifiableList(items)));
        } else {
            out.put(key, new Value(filtered, null));
        }
    }
}
