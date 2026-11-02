package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * S306-1：RT-4 弱比较全矩阵回归。期望值是 PHP 7.3.33 对 27 个值两两组合的实测结果
 * （{@code src/test/php/c17_compare_matrix.php} 生成），重点补 S3-05 黄金未覆盖的 NaN 在左右两侧、
 * NaN 与数字串/非数字串/整数/浮点/数组的混合比较；其余组合同时锁住既有行为不回退。
 */
class PhpSemanticsCompareMatrixTest {

    private static final Path MATRIX = Path.of("src", "test", "resources", "phptemplate-runtime", "rt4-compare-matrix.txt");

    /** 与生成器里的 {@code $vals} 同序同标签。 */
    private static final Map<String, ZValue> VALUES = new LinkedHashMap<>();

    static {
        VALUES.put("NAN", ZValue.of(Double.NaN));
        VALUES.put("INF", ZValue.of(Double.POSITIVE_INFINITY));
        VALUES.put("-INF", ZValue.of(Double.NEGATIVE_INFINITY));
        VALUES.put("i0", ZValue.of(0L));
        VALUES.put("i1", ZValue.of(1L));
        VALUES.put("i-1", ZValue.of(-1L));
        VALUES.put("f0", ZValue.of(0.0));
        VALUES.put("f-0", ZValue.of(-0.0));
        VALUES.put("f1.5", ZValue.of(1.5));
        VALUES.put("s0", ZValue.of("0"));
        VALUES.put("sabc", ZValue.of("abc"));
        VALUES.put("s", ZValue.of(""));
        VALUES.put("s1", ZValue.of("1"));
        VALUES.put("s1.5", ZValue.of("1.5"));
        VALUES.put("s_1", ZValue.of(" 1"));
        VALUES.put("s1abc", ZValue.of("1abc"));
        VALUES.put("sNAN", ZValue.of("NAN"));
        VALUES.put("sINF", ZValue.of("INF"));
        VALUES.put("s1e999", ZValue.of("1e999"));
        VALUES.put("s-1e999", ZValue.of("-1e999"));
        VALUES.put("null", ZValue.NULL);
        VALUES.put("true", ZValue.TRUE);
        VALUES.put("false", ZValue.FALSE);
        VALUES.put("a[]", new ZArray());
        VALUES.put("a[0]", ZArray.list(ZValue.of(0L)));
        VALUES.put("a[NAN]", ZArray.list(ZValue.of(Double.NaN)));
        VALUES.put("a[s0]", ZArray.list(ZValue.of("0")));
    }

    /** 矩阵末尾 "=名称|结果" 行：分别构造的数组（不是同一张表），与生成器里的 {@code $extra} 一一对应。 */
    private static final Map<String, BooleanSupplier> EXTRA = new LinkedHashMap<>();

    static {
        EXTRA.put("distinct [NAN] == [NAN]", () -> PhpSemantics.looseEquals(nanList(), nanList()));
        EXTRA.put("distinct [NAN] <= [NAN]", () -> PhpSemantics.isSmallerOrEqual(nanList(), nanList()));
        // $x >= $y 即 $y <= $x；两边都是新建的 [NAN]
        EXTRA.put("distinct [NAN] >= [NAN]", () -> PhpSemantics.isSmallerOrEqual(nanList(), nanList()));
        EXTRA.put("in_array(NAN, [NAN])", () -> PhpSemantics.inArray(ZValue.of(Double.NaN), ZArray.list(ZValue.of(Double.NaN)), false));
        EXTRA.put("in_array([NAN], [[NAN]])", () -> PhpSemantics.inArray(nanList(), ZArray.list(nanList()), false));
        EXTRA.put("[1, NAN] < [2, NAN]", () -> PhpSemantics.isSmaller(
                ZArray.list(ZValue.of(1L), ZValue.of(Double.NaN)), ZArray.list(ZValue.of(2L), ZValue.of(Double.NaN))));
        EXTRA.put("[NAN, 1] < [NAN, 2]", () -> PhpSemantics.isSmaller(
                ZArray.list(ZValue.of(Double.NaN), ZValue.of(1L)), ZArray.list(ZValue.of(Double.NaN), ZValue.of(2L))));
        EXTRA.put("[NAN, 1] > [NAN, 2]", () -> PhpSemantics.isSmaller(
                ZArray.list(ZValue.of(Double.NaN), ZValue.of(2L)), ZArray.list(ZValue.of(Double.NaN), ZValue.of(1L))));
        EXTRA.put("[a=>NAN] == [a=>NAN]", () -> {
            ZArray x = new ZArray();
            x.put("a", ZValue.of(Double.NaN));
            ZArray y = new ZArray();
            y.put("a", ZValue.of(Double.NaN));
            return PhpSemantics.looseEquals(x, y);
        });
        EXTRA.put("[\"0\"] == [\"0.0\"]", () -> PhpSemantics.looseEquals(ZArray.list(ZValue.of("0")), ZArray.list(ZValue.of("0.0"))));
    }

    @Test
    void looseComparisonMatrixMatchesPhp73() throws IOException {
        List<String> lines = Files.readAllLines(MATRIX, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).as("矩阵须由 PHP 7.3 64 位生成").startsWith("# PHP 7.3.").contains("64-bit");
        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        int extras = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("=")) {
                int bar = line.lastIndexOf('|');
                String name = line.substring(1, bar);
                String expected = line.substring(bar + 1);
                BooleanSupplier op = EXTRA.get(name);
                assertThat(op).as("未知补充用例 " + name).isNotNull();
                String actual = op.getAsBoolean() ? "1" : "0";
                if (!actual.equals(expected)) {
                    mismatches.add(name + ": php=" + expected + " java=" + actual);
                }
                extras++;
                continue;
            }
            String[] f = line.split("\\|", -1);
            ZValue a = VALUES.get(f[0]);
            ZValue b = VALUES.get(f[1]);
            assertThat(a).as("未知标签 " + f[0]).isNotNull();
            assertThat(b).as("未知标签 " + f[1]).isNotNull();
            String actual = bits(a, b);
            if (!actual.equals(f[2])) {
                mismatches.add(f[0] + " vs " + f[1] + ": php=" + f[2] + " java=" + actual);
            }
            rows++;
        }
        assertThat(rows).as("27×27 组合").isEqualTo(VALUES.size() * VALUES.size());
        assertThat(extras).as("补充用例").isEqualTo(EXTRA.size());
        assertThat(mismatches).as("列序 == != < > <= >= in_array").isEmpty();
    }

    @Test
    void nanIsUncomparableWithNumbersAndStringsOnEitherSide() {
        ZValue nan = ZValue.of(Double.NaN);
        for (ZValue other : List.of(ZValue.of(0L), ZValue.of(1L), ZValue.of(0.0), ZValue.of(1.5),
                ZValue.of("0"), ZValue.of("abc"), ZValue.of(""), ZValue.of("1.5"), ZValue.of("1abc"), nan)) {
            String label = "NAN vs " + PhpSemantics.toStr(other);
            assertThat(bits(nan, other)).as(label).isEqualTo("0100000");
            assertThat(bits(other, nan)).as("反向 " + label).isEqualTo("0100000");
        }
    }

    private static ZArray nanList() {
        return ZArray.list(ZValue.of(Double.NaN));
    }

    /** 与生成器同一列序；{@code >}、{@code >=} 按 Zend 交换操作数走 {@code <}、{@code <=}。 */
    private static String bits(ZValue a, ZValue b) {
        boolean[] r = {
            PhpSemantics.looseEquals(a, b),
            !PhpSemantics.looseEquals(a, b),
            PhpSemantics.isSmaller(a, b),
            PhpSemantics.isSmaller(b, a),
            PhpSemantics.isSmallerOrEqual(a, b),
            PhpSemantics.isSmallerOrEqual(b, a),
            PhpSemantics.inArray(b, ZArray.list(a), false),
        };
        StringBuilder sb = new StringBuilder(r.length);
        for (boolean x : r) {
            sb.append(x ? '1' : '0');
        }
        return sb.toString();
    }
}
