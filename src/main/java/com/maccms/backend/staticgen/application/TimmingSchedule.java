package com.maccms.backend.staticgen.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 定时任务是否到点（旧 api/controller/Timming.php:21-37，B16，25 文档第 7.2 节），逐字照旧：
 * <pre>
 * status == '1' && ( empty(runtime) || (旧"周-时" != 现"周-时" && strpos(weeks, 现周) !== false && strpos(hours, 现时) !== false)
 *                    || enforce == '1' )
 * </pre>
 * 周取 {@code date('w')}（0=周日），时取 {@code date('H')}（两位）；{@code strpos} 是子串查找（{@code hours='109'} 也含 {@code '09'}，照旧）。
 * 与 PHP 黄金向量逐条一致（{@code TimmingScheduleGoldenTest}）。
 */
public final class TimmingSchedule {

    private static final DateTimeFormatter LAST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** PHP 8 {@code is_numeric}：前后可有空白，正负号、小数与指数，不接受十六进制与空串。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");

    /**
     * @param due  是否执行
     * @param last 上次执行时间的显示（{@code Y-m-d H:i:s}；没有时为调用方给的"从未"）
     */
    public record Decision(boolean due, String last) {
    }

    private TimmingSchedule() {
    }

    /**
     * @param task    一个定时任务（{@code extra/timming.php} 的一项）
     * @param now     当前时间（秒）
     * @param enforce 请求参数 {@code enforce}（没有时为空串）
     * @param never   语言包 {@code api/never}
     */
    public static Decision decide(PhpArray task, long now, String enforce, ZoneId zone, String never) {
        PhpValue runtime = task.get("runtime");
        boolean hasRuntime = !PhpValues.empty(runtime);
        ZonedDateTime cur = Instant.ofEpochSecond(now).atZone(zone);
        String curWeek = Integer.toString(cur.getDayOfWeek().getValue() % 7);
        String curHours = String.format("%02d", cur.getHour());
        String old = "-";
        String last = never;
        if (hasRuntime) {
            ZonedDateTime o = Instant.ofEpochSecond(seconds(runtime)).atZone(zone);
            old = (o.getDayOfWeek().getValue() % 7) + "-" + String.format("%02d", o.getHour());
            last = o.format(LAST);
        }
        boolean due = EpisodeCounts.looseEqualsOne(task.get("status"))
                && (!hasRuntime
                || (!old.equals(curWeek + "-" + curHours)
                && PhpValues.text(task.get("weeks")).contains(curWeek)
                && PhpValues.text(task.get("hours")).contains(curHours))
                || looseEqualsOne(enforce));
        return new Decision(due, last);
    }

    /** {@code date()} 的时间参数按 PHP 转成整数（整数原样；数字串、浮点取整数部分）。 */
    private static long seconds(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> (long) f.value();
            case PhpValue.PhpBool b -> b.value() ? 1 : 0;
            default -> PhpIntval.of(PhpValues.text(v));
        };
    }

    /** PHP 8 字符串 {@code $s == '1'}：两边都是数字串时按数值比较，否则按字节比较。 */
    public static boolean looseEqualsOne(String s) {
        if (s == null) {
            return false;
        }
        if (NUMERIC.matcher(s).matches()) {
            try {
                return new BigDecimal(s.strip()).compareTo(BigDecimal.ONE) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return s.equals("1");
    }
}
