package com.maccms.backend.content.infrastructure;

/**
 * 点击/来路的日、周、月计数是否沿用（旧 {@code Ajax::hits}/{@code referer}，application/index/controller/Ajax.php:178-199、
 * :261-282）的时间区间，Unix 秒，由 content.application 按 PRC 求得：上次计数时间落在区间内时 {@code +1}，否则置为 1。
 *
 * @param dayStart   今天 0 点（含）
 * @param dayEnd     明天 0 点（不含）
 * @param weekStart  本周日 0 点（含；旧版 {@code mktime(0,0,0,…) - wday*86400}）
 * @param weekEnd    本周六 23:59:59（含；旧版 {@code mktime(23,59,59,…) + (6-wday)*86400}）
 * @param monthStart 本月 1 日 0 点（含）
 * @param monthEnd   下月 1 日 0 点（不含）
 * @param now        本次计数时间，写入 {@code *_time_hits}/{@code website_time_referer}
 */
public record HitPeriods(long dayStart, long dayEnd, long weekStart, long weekEnd, long monthStart, long monthEnd,
                         long now) {
}
