package com.maccms.backend.member.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧版 {@code json($res)} 的响应（B10，docs/architecture/19-b10-member-finance.md 第 4 节）：正文按旧代码数组的键顺序（一般是
 * {@code code}、{@code msg}，有的入口另带 {@code info}/{@code data}），个别入口外层是数组（{@code ajax_buy_popedom} 的
 * {@code json([$res])}）。由 site 用 {@code LegacyJson} 编码，与 PHP {@code json_encode(…, JSON_UNESCAPED_UNICODE)} 逐字节相同。
 *
 * @param body       {@link Map}（有序）或 {@link List}
 * @param setCookies 要写出的 {@code Set-Cookie}（升级会员组时的 {@code group_id}/{@code group_name}）
 */
public record JsonReply(Object body, List<String> setCookies) {

    public JsonReply {
        if (!(body instanceof Map<?, ?>) && !(body instanceof List<?>)) {
            throw new IllegalArgumentException("body 须是 Map 或 List");
        }
        setCookies = List.copyOf(setCookies);
    }

    public static JsonReply of(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        return new JsonReply(Collections.unmodifiableMap(m), List.of());
    }

    /** @param body 有序的键值（调用方不再修改） */
    public static JsonReply of(Map<String, Object> body) {
        return new JsonReply(Collections.unmodifiableMap(body), List.of());
    }

    /** 外层包一层数组（旧 {@code json([$res])}）。 */
    public JsonReply wrapped() {
        return new JsonReply(List.of(body), setCookies);
    }

    public JsonReply withCookies(List<String> cookies) {
        return new JsonReply(body, cookies);
    }

    /** 正文（数组形式取第一个元素）的 {@code code}。 */
    public int code() {
        Object c = first().get("code");
        return c instanceof Number n ? n.intValue() : 0;
    }

    /** 正文（数组形式取第一个元素）的 {@code msg}。 */
    public String msg() {
        Object m = first().get("msg");
        return m instanceof String s ? s : "";
    }

    public boolean ok() {
        return code() == 1;
    }

    private Map<?, ?> first() {
        if (body instanceof Map<?, ?> m) {
            return m;
        }
        List<?> list = (List<?>) body;
        return !list.isEmpty() && list.get(0) instanceof Map<?, ?> m ? m : Map.of();
    }
}
