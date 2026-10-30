package com.maccms.backend.common.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按旧 PHP 的方式解析查询串与 {@code application/x-www-form-urlencoded} 正文（B09，docs/architecture/18-b09-member-account.md
 * 第 3.1 节），纯计算，<b>按字节</b>：不经 Servlet 容器的字符集转换，非法 UTF-8、{@code \0} 等字节原样保留，供密码等需要字节语义的
 * 字段使用（03 草稿第 4.3 (1) 节）。
 *
 * <p>规则（PHP {@code php_default_treat_data} + {@code php_register_variable_ex}，对 GET 与 POST 相同）：按 {@code &} 分段，空段忽略；
 * 段内第一个 {@code =} 前为名、后为值，没有 {@code =} 时值为空串；名与值都按 {@code urldecode} 解码一次（{@code +} 为空格、
 * {@code %XX} 为一个字节）；名前的空格去掉，名中的空格与 {@code .} 变 {@code _}；带 {@code [...]} 的名是数组（只标记，不保留内容），
 * 没有配对 {@code ]} 的 {@code [} 变 {@code _}；名为空时整段忽略；同名时<b>后到的覆盖先到的</b>（与 cookie 的先到先得不同）。
 *
 * <p>与 ThinkPHP {@code input()} 的合并顺序见 {@link #merge}。
 */
public final class PhpInput {

    private PhpInput() {
    }

    /** 一个参数值。{@code array} 为 true 表示旧 PHP 中是数组，此时 {@link #bytes()} 为空。 */
    public record Value(byte[] bytes, boolean array) {

        public static final Value EMPTY = new Value(new byte[0], false);

        /** 按 UTF-8 解释的文本（非法序列替换为 U+FFFD；需要逐字节处理时用 {@link #bytes()}）。 */
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析原始字节（查询串取请求行里 {@code ?} 之后的部分，按 ISO-8859-1 转成字节；正文取原始字节）。
     *
     * @return 名（按 UTF-8 解释）→ 值，按首次出现的顺序；不可变
     */
    public static Map<String, Value> parse(byte[] raw) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (raw == null || raw.length == 0) {
            return Map.of();
        }
        int start = 0;
        for (int i = 0; i <= raw.length; i++) {
            if (i == raw.length || raw[i] == '&') {
                if (i > start) {
                    segment(out, raw, start, i);
                }
                start = i + 1;
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 查询串（{@code HttpServletRequest#getQueryString()} 是请求行原样的 ASCII，按 ISO-8859-1 取字节）。 */
    public static Map<String, Value> parseQuery(String queryString) {
        return queryString == null ? Map.of() : parse(queryString.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * ThinkPHP 5.0 {@code Request::param()} 的合并：GET → POST（POST 覆盖 GET）→ 路由参数（覆盖前两者）。
     *
     * @param route 路径中的 {@code 键/值}（已按 PATH_INFO 解码一次，不再解码）
     */
    public static Map<String, Value> merge(Map<String, Value> get, Map<String, Value> post, Map<String, String> route) {
        Map<String, Value> out = new LinkedHashMap<>(get);
        out.putAll(post);
        for (Map.Entry<String, String> e : route.entrySet()) {
            out.put(e.getKey(), new Value(e.getValue().getBytes(StandardCharsets.UTF_8), false));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 取标量字段的原始字节；缺失或是数组时为 {@code null}。 */
    public static byte[] scalar(Map<String, Value> params, String name) {
        Value v = params.get(name);
        return v == null || v.array() ? null : v.bytes();
    }

    private static void segment(Map<String, Value> out, byte[] raw, int from, int to) {
        int eq = -1;
        for (int i = from; i < to; i++) {
            if (raw[i] == '=') {
                eq = i;
                break;
            }
        }
        byte[] name = PhpCookies.urlDecode(Arrays.copyOfRange(raw, from, eq < 0 ? to : eq));
        byte[] value = eq < 0 ? new byte[0] : PhpCookies.urlDecode(Arrays.copyOfRange(raw, eq + 1, to));
        register(out, new String(name, StandardCharsets.ISO_8859_1), value);
    }

    /** {@code php_register_variable_ex} 的名字规则（只区分标量与数组）；名按字节处理，最后按 UTF-8 解释。 */
    private static void register(Map<String, Value> out, String name, byte[] value) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == ' ') {
            i++;
        }
        name = name.substring(i);
        int bracket = name.indexOf('[');
        boolean array = false;
        String base = name;
        if (bracket >= 0) {
            if (name.indexOf(']', bracket) >= 0) {
                array = true;
                base = name.substring(0, bracket);
            } else {
                base = name.substring(0, bracket) + "_" + name.substring(bracket + 1);
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
        out.put(key, array ? new Value(new byte[0], true) : new Value(value, false));
    }
}
