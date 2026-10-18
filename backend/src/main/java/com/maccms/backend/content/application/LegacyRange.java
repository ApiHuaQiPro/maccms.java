package com.maccms.backend.content.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 旧模板标签的点击数范围条件（{@code hits}、{@code hitsday}、{@code hitsweek}、{@code hitsmonth}，
 * application/common/model/Vod.php:363-398）：旧值按空格切成"运算符 值"两段交给 ThinkPHP 的 where 表达式
 * （thinkphp/library/think/db/Builder.php:296-352 的 {@code $exp} 表）。
 *
 * <p>新版只接受比较与区间类运算符（白名单），值只能是整数，SQL 中运算符由 XML 的 {@code <choose>} 写死，值只经 {@code #{}} 绑定，
 * 所以不会拼接任何不受控的 SQL。旧版能写的 {@code exp}（原样 SQL）、{@code like}、{@code null}、{@code exists}、时间运算符等不支持；
 * 只有一段（如 {@code 100}）时旧版把数组当值拼进 SQL、语句出错，新版同样拒绝（docs/architecture/15-b05-readonly-pages.md 第 2.4 节）。
 *
 * @param op     运算符
 * @param values 比较值：比较运算 1 个，区间 2 个，IN / NOT IN 1..{@value #MAX_VALUES} 个
 */
public record LegacyRange(Op op, List<Long> values) {

    /** IN / NOT IN 的项数上限（新版的请求大小上限；旧版不限）。 */
    public static final int MAX_VALUES = 100;

    /** 运算符；{@link #sql()} 只用于文档与测试，SQL 文本由 VodMapper.xml 按枚举名写死。 */
    public enum Op {
        EQ("="), NEQ("<>"), GT(">"), EGT(">="), LT("<"), ELT("<="), BETWEEN("BETWEEN"), NOT_BETWEEN("NOT BETWEEN"),
        IN("IN"), NOT_IN("NOT IN");

        private final String sql;

        Op(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    /** 旧运算符写法（不区分大小写，Builder.php 的 $exp 表中键与值都可用）→ 新运算符。 */
    private static final Map<String, Op> LEGACY_OPS = Map.ofEntries(
            Map.entry("eq", Op.EQ), Map.entry("=", Op.EQ),
            Map.entry("neq", Op.NEQ), Map.entry("<>", Op.NEQ),
            Map.entry("gt", Op.GT), Map.entry(">", Op.GT),
            Map.entry("egt", Op.EGT), Map.entry(">=", Op.EGT),
            Map.entry("lt", Op.LT), Map.entry("<", Op.LT),
            Map.entry("elt", Op.ELT), Map.entry("<=", Op.ELT),
            Map.entry("between", Op.BETWEEN), Map.entry("notbetween", Op.NOT_BETWEEN),
            Map.entry("in", Op.IN), Map.entry("notin", Op.NOT_IN));

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,18}");

    public LegacyRange {
        if (op == null || values == null) {
            throw new IllegalArgumentException("范围条件缺少运算符或值");
        }
        values = List.copyOf(values);
        int n = values.size();
        boolean ok = switch (op) {
            case BETWEEN, NOT_BETWEEN -> n == 2;
            case IN, NOT_IN -> n >= 1 && n <= MAX_VALUES;
            default -> n == 1;
        };
        if (!ok) {
            throw new IllegalArgumentException("范围条件的值个数不合法");
        }
    }

    public static LegacyRange of(Op op, long... values) {
        List<Long> v = new ArrayList<>(values.length);
        for (long x : values) {
            v.add(x);
        }
        return new LegacyRange(op, v);
    }

    /**
     * 旧标签属性写法：{@code explode(' ', $v)} 后恰好两段"运算符 值"（Vod.php:364-370）。值：比较运算为一个整数，
     * {@code between}/{@code notbetween} 为逗号分隔的两个整数，{@code in}/{@code notin} 为逗号分隔的整数（ThinkPHP 对字符串值
     * {@code explode(',')}，Builder.php:330-345）。不支持的写法抛 {@link IllegalArgumentException}（信息不含取值）。
     */
    public static LegacyRange parseLegacy(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("范围条件为空");
        }
        String[] parts = raw.split(" ", -1);
        if (parts.length != 2) {
            // 一段：旧版把数组拼进 SQL（语句出错）；三段及以上：旧版只取前两段，运算符不合法时抛异常
            throw new IllegalArgumentException("范围条件必须是“运算符 值”两段");
        }
        Op op = LEGACY_OPS.get(parts[0].toLowerCase(Locale.ROOT));
        if (op == null) {
            throw new IllegalArgumentException("不支持的范围运算符");
        }
        List<Long> values = new ArrayList<>();
        for (String s : op == Op.BETWEEN || op == Op.NOT_BETWEEN || op == Op.IN || op == Op.NOT_IN
                ? parts[1].split(",", -1) : new String[] {parts[1]}) {
            if (!INTEGER.matcher(s).matches()) {
                throw new IllegalArgumentException("范围条件的值必须是整数");
            }
            values.add(Long.parseLong(s));
        }
        return new LegacyRange(op, values);
    }

    /** 比较运算的值（XML 用）。 */
    public long value() {
        return values.get(0);
    }

    /** 区间下界（XML 用）。 */
    public long low() {
        return values.get(0);
    }

    /** 区间上界（XML 用）。 */
    public long high() {
        return values.get(1);
    }
}
