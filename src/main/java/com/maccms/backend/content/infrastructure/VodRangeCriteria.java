package com.maccms.backend.content.infrastructure;

import java.util.List;
import java.util.Set;

/**
 * 点击数范围条件的绑定值（VodMapper.xml {@code legacyConditions}）。{@code op} 只能是固定的运算符名，XML 用 {@code <choose>}
 * 按名字写出固定的 SQL 运算符；值只经 {@code #{}} 绑定。由 content.application 用已校验的条件构造。
 *
 * @param op     EQ、NEQ、GT、EGT、LT、ELT、BETWEEN、NOT_BETWEEN、IN、NOT_IN 之一
 * @param values 比较运算 1 个，区间 2 个，IN / NOT IN 至少 1 个
 */
public record VodRangeCriteria(String op, List<Long> values) {

    private static final Set<String> OPS = Set.of("EQ", "NEQ", "GT", "EGT", "LT", "ELT", "BETWEEN", "NOT_BETWEEN",
            "IN", "NOT_IN");

    public VodRangeCriteria {
        if (op == null || !OPS.contains(op) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("范围条件不合法");
        }
        values = List.copyOf(values);
        boolean between = op.equals("BETWEEN") || op.equals("NOT_BETWEEN");
        boolean list = op.equals("IN") || op.equals("NOT_IN");
        if ((between && values.size() != 2) || (!between && !list && values.size() != 1)) {
            throw new IllegalArgumentException("范围条件的值个数不合法");
        }
    }

    public long value() {
        return values.get(0);
    }

    public long low() {
        return values.get(0);
    }

    public long high() {
        return values.get(1);
    }
}
