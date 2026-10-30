package com.maccms.backend.content.application;

/**
 * Provide 的 {@code datafilter} 经白名单语法解析后的一个条件（B06，16 文档第 3.4 节）：{@code <前缀>_<列> <运算符> <整数>}。
 * 列与运算符都是枚举，SQL 中的写法固定在 LegacyRowMapper.xml。
 *
 * @param column 列（{@link Column#ISEND}、{@link Column#COPYRIGHT}、{@link Column#PLOT} 只用于视频）
 * @param op     运算符
 * @param value  比较值
 */
public record LegacyCondition(Column column, Op op, long value) {

    public enum Column {
        STATUS, LEVEL, LOCK, ISEND, COPYRIGHT, PLOT;

        /** 该列是否存在于这张表。 */
        public boolean appliesTo(LegacyTable table) {
            return switch (this) {
                case STATUS, LEVEL, LOCK -> true;
                case ISEND, COPYRIGHT, PLOT -> table == LegacyTable.VOD;
            };
        }
    }

    public enum Op {
        EQ("="), NE("!="), GT(">"), GE(">="), LT("<"), LE("<=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    public LegacyCondition {
        if (column == null || op == null) {
            throw new IllegalArgumentException("缺少列或运算符");
        }
    }
}
