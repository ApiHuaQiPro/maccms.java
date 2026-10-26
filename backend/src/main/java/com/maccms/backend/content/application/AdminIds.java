package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 后台内容管理的主键与编号列表（B13，22 文档第 4.3 节）。
 */
public final class AdminIds {

    /** 一次删除、修改的编号上限（与 B12 管理员删除相同）。 */
    public static final int MAX_IDS = 1000;

    private AdminIds() {
    }

    /**
     * 表单页的 {@code input('id')}：旧版 {@code where 主键 = $id}（MySQL 把字符串按整数比较）；新版按 PHP {@code intval}，小于 1 时没有这一行。
     */
    public static Optional<Long> lookupId(byte[] id) {
        if (id == null) {
            return Optional.empty();
        }
        long v = PhpIntval.of(new String(id, StandardCharsets.ISO_8859_1));
        return v < 1 ? Optional.empty() : Optional.of(v);
    }

    /**
     * 编辑时的主键（旧版 {@code !empty($data['x_id'])} 后 {@code where x_id = 值} 并把同一个值写回主键列）：新版要求十进制数字（可带首尾空格），
     * 否则"参数错误"（旧版对 {@code 2abc} 一类取值在写回主键列时报错 500）。
     */
    public static long primaryKey(byte[] raw) {
        Long v = digits(raw);
        if (v == null) {
            throw AdminRejectedException.paramError();
        }
        return v;
    }

    /**
     * 删除、修改的 {@code ids}（旧版 {@code where 主键 in $ids}：数组取各项，字符串按逗号拆分，交给 MySQL 隐式转换）：缺失或为 PHP
     * {@code empty()} 时为 null（调用方提示"参数错误"）；每项必须是十进制数字（可带首尾空格），否则"参数错误"（21 文档 D-B12-10 的同一规则）；
     * 最多 {@link #MAX_IDS} 项。
     */
    public static List<Long> ids(PhpForm param) {
        return ids(param, "ids");
    }

    public static List<Long> ids(PhpForm param, String name) {
        List<byte[]> items;
        if (param.isArray(name)) {
            PhpForm.Arr a = param.array(name);
            if (a.size() == 0) {
                return null;
            }
            items = new ArrayList<>(a.entries().values());
        } else {
            byte[] s = param.scalar(name);
            if (PhpForm.phpEmpty(s)) {
                return null;
            }
            items = split(s);
        }
        if (items.size() > MAX_IDS) {
            throw AdminRejectedException.paramError();
        }
        List<Long> out = new ArrayList<>();
        for (byte[] it : items) {
            Long v = digits(it);
            if (v == null) {
                throw AdminRejectedException.paramError();
            }
            out.add(v);
        }
        return out;
    }

    /**
     * 数组参数第 {@code k} 项（PHP {@code $param[name][$k]}）写进 {@code data[target]}：没有这一项时是 PHP 的 null（写库时 NOT NULL 列报错，
     * 新版"参数错误"）；参数是字符串时旧版取到的是第 k 个字符，新版按非法参数"参数错误"。
     */
    public static void item(PhpForm data, String target, PhpForm param, String name, String k) {
        if (param.has(name) && !param.isArray(name)) {
            throw AdminRejectedException.paramError();
        }
        PhpForm.Arr a = param.array(name);
        byte[] v = a == null ? null : a.entries().get(k);
        if (v == null) {
            data.putNull(target);
        } else {
            data.put(target, v);
        }
    }

    /** 按逗号拆分（PHP {@code explode(',', $s)}）。 */
    public static List<byte[]> split(byte[] s) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length; i++) {
            if (i == s.length || s[i] == ',') {
                out.add(java.util.Arrays.copyOfRange(s, start, i));
                start = i + 1;
            }
        }
        return out;
    }

    /** 十进制数字（可带首尾空格，最多 10 位）；否则 null。 */
    static Long digits(byte[] raw) {
        if (raw == null) {
            return null;
        }
        String s = new String(raw, StandardCharsets.ISO_8859_1).strip();
        if (s.isEmpty() || s.length() > 10 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Long.parseLong(s);
    }
}
