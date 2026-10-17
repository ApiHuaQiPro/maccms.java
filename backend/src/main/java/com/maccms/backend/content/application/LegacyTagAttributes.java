package com.maccms.backend.content.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 旧模板标签 {@code {maccms:vod …}} 属性值（字符串）到 {@link VideoLegacyConditions} 各项的换算，复刻
 * {@code Vod::listCacheData}（application/common/model/Vod.php:108-499）与 ThinkPHP 条件绑定的结果。新版不解析旧模板语法；
 * 这里只把属性值的语义固定下来，供页面（或将来的模板转换）给出与旧标签等价的条件，并由 PHP 黄金样本逐项验证
 * （backend/src/test/php/vod_tag_golden.php）。
 *
 * <p>整数列上的列表（{@code not}、{@code typenot}、{@code level}、数字 {@code rel}）：旧版 {@code explode(',')} 后按整数绑定，
 * 每项的取值与 {@link PhpIntval} 相同（空项为 0）。{@code type} 的各项与分类 ID 做 PHP 宽松比较（{@code in_array}），
 * 只有数字字符串才可能相等。
 */
public final class LegacyTagAttributes {

    private LegacyTagAttributes() {
    }

    /**
     * 旧 type（Vod.php:337-351）：{@code all}、空串与 {@code "0"}（{@code empty()}）为不筛选（返回空）；{@code current} 为当前分类（{@code currentTypeId}，
     * 旧 {@code intval($GLOBALS['type_id'])}，没有当前分类时为 0，即全部顶级分类）；否则逗号分隔的各项中是整数的数字字符串
     * （PHP 宽松比较能等于某个分类 ID 的写法，如 {@code "6"}、{@code " 6"}、{@code "06"}、{@code "6.0"}）取其值，其余项忽略；
     * 一项都不是时为空列表（不命中任何分类）。
     */
    public static Optional<List<Integer>> types(String raw, int currentTypeId) {
        if (raw == null || raw.isEmpty() || raw.equals("0") || raw.equals("all")) {
            return Optional.empty();
        }
        if (raw.equals("current")) {
            return Optional.of(List.of(currentTypeId));
        }
        List<Integer> out = new ArrayList<>();
        for (String item : VideoFilter.items(raw)) {
            if (PhpValues.isNumeric(item)) {
                double d = Double.parseDouble(item.strip());
                if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                    out.add((int) d);
                }
            }
        }
        return Optional.of(List.copyOf(out));
    }

    /** 旧 not / typenot：逗号分隔，各项按 intval（空项为 0）。空串为不筛选。 */
    public static Optional<List<Long>> intList(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("0")) {
            return Optional.empty();
        }
        List<Long> out = new ArrayList<>();
        for (String item : VideoFilter.items(raw)) {
            out.add(PhpIntval.of(item));
        }
        return Optional.of(List.copyOf(out));
    }

    /** {@link #intList} 换成 int（超出 int 的值饱和到上下限：这些列都是 smallint/tinyint，饱和后仍不命中）。 */
    public static Optional<List<Integer>> smallIntList(String raw) {
        return intList(raw).map(l -> l.stream().map(LegacyTagAttributes::saturate).toList());
    }

    /** 旧 level（Vod.php:285-290）：{@code all} 为 1..9，否则同 {@link #smallIntList}。 */
    public static Optional<List<Integer>> levels(String raw) {
        if ("all".equals(raw)) {
            return Optional.of(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        }
        return smallIntList(raw);
    }

    /**
     * 旧 rel（Vod.php:281-288）：整个值是数字字符串，或逗号拆开后每项都是数字字符串（{@code is_numeric}）时为 vod_id 列表
     * （{@link Rel#ids()}，各项按 intval）；否则为 vod_rel_vod 上的包含匹配文本（{@link Rel#text()}）。
     */
    public static Optional<Rel> rel(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("0")) {
            return Optional.empty();
        }
        List<String> items = VideoFilter.items(raw);
        if (PhpValues.isNumeric(raw) || items.stream().allMatch(PhpValues::isNumeric)) {
            return Optional.of(new Rel(items.stream().map(PhpIntval::of).toList(), null));
        }
        return Optional.of(new Rel(null, raw));
    }

    /** {@link #rel} 的结果：二者恰有一个非空。 */
    public record Rel(List<Long> ids, String text) {
    }

    /**
     * 旧 isend / plot（Vod.php:396-398、:484-486）：{@code in_array($v, ['0','1'])}（宽松比较）为真时取 0 或 1，否则不筛选。
     * 数字字符串按数值比较（{@code "01"}、{@code " 1"}、{@code "1.0"} 都等于 1）。
     */
    public static Optional<Integer> zeroOrOne(String raw) {
        if (raw == null || !PhpValues.isNumeric(raw)) {
            return Optional.empty();
        }
        double d = Double.parseDouble(raw.strip());
        if (d == 0) {
            return Optional.of(0);
        }
        if (d == 1) {
            return Optional.of(1);
        }
        return Optional.empty();
    }

    /**
     * 旧 start / num（{@code intval(abs($v))}，Vod.php:134-136）：数字字符串取绝对值后截断为整数；空串为 0。
     * 不是数字字符串时抛 {@link IllegalArgumentException}（PHP 8 的 {@code abs()} 对非数字字符串抛 TypeError）。
     */
    public static int absInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        if (!PhpValues.isNumeric(raw)) {
            throw new IllegalArgumentException("不是数字");
        }
        double d = Math.abs(Double.parseDouble(raw.strip()));
        return d >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) d;
    }

    /** 旧 tv：{@code explode(',')}，保留空项（Vod.php:315-317）。空串为不筛选。 */
    public static Optional<List<String>> textList(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("0")) {
            return Optional.empty();
        }
        return Optional.of(VideoFilter.items(raw));
    }

    private static int saturate(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) v;
    }
}
