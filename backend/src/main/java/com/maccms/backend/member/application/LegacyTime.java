package com.maccms.backend.member.application;

import com.maccms.backend.content.application.AdminRejectedException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B14 后台用到的 PHP {@code strtotime} 的两个子集（23 文档第 4.3 节），时区 PRC（旧代码 {@code date_default_timezone_set('PRC')}）：
 * <ul>
 * <li>{@link #formDateTime}：会员表单的到期时间（旧 {@code User::saveData}：不是数字串时 {@code strtotime($v)}，失败的 {@code false} 写库为 0）。
 * 按 PHP 8.0/7.3 实测（23 文档第 4.3 节）实现后台日期控件写出的 {@code Y-m-d H:i:s} 一类写法：日期 {@code 年(4 位)-月-日}（月 0～12、日 0～31，
 * 一到两位数字，0 与超出当月的日按 PHP 顺延，如 {@code 2030-02-30} 为 3 月 2 日、{@code 2030-00-10} 为 2029-12-10），可接时间 {@code 时:分[:秒]}
 * （时 0～24、分 0～59、秒 0～60，一到两位数字；与日期之间为空格、制表符或 {@code T}），首尾可有空白。同一形状但字段越界（月 13、日 32、时 25、
 * 分 60、秒 61、只有时没有分）时 {@code strtotime} 失败，写 0；空串同样写 0（黄金 {@code user-save/edit-end-time-empty}）；只有空白时
 * {@code strtotime} 返回当前时间。其他写法（{@code now}、{@code +1 day}、{@code 2030/01/02} 等）不实现，按"参数错误"拒绝（D-B14-5）。</li>
 * <li>{@link #daysAgoMidnight}：卡密与推广访问列表的时间筛选 {@code strtotime(date('Y-m-d', strtotime('-'.$time.' day')))}：{@code time}
 * 是 1～5 位十进制数字时为 N 天前的零点；其余取值（空串、{@code abc} 等）同 {@code strtotime} 失败，{@code date('Y-m-d', false)} 为
 * 1970-01-01，结果是 -28800（列出全部，黄金 {@code card-list/time-empty}、{@code time-bad}）。</li>
 * </ul>
 */
final class LegacyTime {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    /** {@code strtotime('1970-01-01')}（PRC）。 */
    static final long EPOCH_DAY_PRC = -28800;

    /** 日期部分的形状（字段是否越界另判）。 */
    private static final Pattern FORM = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})"
            + "(?:[ \\t]*[ \\tT][ \\t]*([0-9]{1,2})(?::([0-9]{1,2})(?::([0-9]{1,2}))?)?)?[ \\t\\n\\r\\x0B\\f]*");
    private static final Pattern DAYS = Pattern.compile("[0-9]{1,5}");

    private LegacyTime() {
    }

    /**
     * 表单里的日期时间 → 秒（可为负，写库时由 MySQL 按无符号列拒绝）；{@code strtotime} 失败时为 0。
     *
     * @param now 当前时间（只有空白时的结果）
     * @throws AdminRejectedException 不是支持的写法
     */
    static long formDateTime(String s, long now) {
        if (s.isEmpty()) {
            return 0;
        }
        if (s.isBlank()) {
            return now;
        }
        Matcher m = FORM.matcher(s);
        if (!m.matches()) {
            throw AdminRejectedException.paramError();
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (m.group(4) != null && m.group(5) == null) {
            // "2030-01-02 03"：PHP 不认只有时的写法
            return 0;
        }
        int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        if (month > 12 || day > 31 || hour > 24 || minute > 59 || second > 60) {
            return 0;
        }
        try {
            LocalDateTime t = LocalDateTime.of(year, 1, 1, 0, 0).plusMonths(month - 1L).plusDays(day - 1L)
                    .plusHours(hour).plusMinutes(minute).plusSeconds(second);
            return t.atZone(PRC).toEpochSecond();
        } catch (DateTimeException e) {
            throw AdminRejectedException.paramError();
        }
    }

    /** 列表的时间筛选下限（规则见类说明）。 */
    static long daysAgoMidnight(String time, long now) {
        if (time == null || !DAYS.matcher(time).matches()) {
            return EPOCH_DAY_PRC;
        }
        LocalDate today = Instant.ofEpochSecond(now).atZone(PRC).toLocalDate();
        return today.minusDays(Long.parseLong(time)).atStartOfDay(PRC).toEpochSecond();
    }
}
