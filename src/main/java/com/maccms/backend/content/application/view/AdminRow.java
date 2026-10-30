package com.maccms.backend.content.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台页面用到的一行旧表数据（B13）：列名 → MySQL 给出的文字（整数为十进制，decimal 保留小数位如 {@code 9.3}，字符串原样），与旧版
 * mysqlnd 取回后在模板里输出的文字相同。缺失的列为 null（旧模板输出空串）。
 */
public record AdminRow(Map<String, String> values) {

    public AdminRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 列的文字；没有该列时为空串（旧模板 {@code {$vo.x}} 对不存在的键输出空串）。 */
    public String s(String column) {
        String v = values.get(column);
        return v == null ? "" : v;
    }

    /** 整数列的值；没有或不是整数时为 0。 */
    public long n(String column) {
        String v = values.get(column);
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean has(String column) {
        return values.containsKey(column);
    }
}
