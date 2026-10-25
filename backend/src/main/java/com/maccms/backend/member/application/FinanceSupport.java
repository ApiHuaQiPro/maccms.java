package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.PhpIntval;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 会员财务各服务共用的参数读取与时间（B10）。 */
final class FinanceSupport {

    /** 列表页大小的上限（旧版没有上限，D-B10-6）。 */
    static final int MAX_LIMIT = 100;
    /** 列表页码的上限（超过时按空页）。 */
    static final int MAX_PAGE = 100_000;
    /** 删除参数 {@code ids} 的项数上限（D-B10-6）。 */
    static final int MAX_IDS = 1000;

    private FinanceSupport() {
    }

    /** 参数原值（不存在或是数组时为空字节）。 */
    static byte[] raw(Map<String, PhpInput.Value> params, String name) {
        byte[] v = PhpInput.scalar(params, name);
        return v == null ? new byte[0] : v;
    }

    /** 参数原值按字节（ISO-8859-1）转成的字符串，用于白名单精确比较与 {@code intval}。 */
    static String text(Map<String, PhpInput.Value> params, String name) {
        return new String(raw(params, name), StandardCharsets.ISO_8859_1);
    }

    /** 参数原值按 UTF-8（金额等）。 */
    static String utf8(Map<String, PhpInput.Value> params, String name) {
        return new String(raw(params, name), StandardCharsets.UTF_8);
    }

    static long intval(Map<String, PhpInput.Value> params, String name) {
        return PhpIntval.of(text(params, name));
    }

    /** {@code intval} 截到 int 范围（列宽内的值；越界的值仍然越界，由写库时的严格模式拒绝）。 */
    static int intvalInt(Map<String, PhpInput.Value> params, String name) {
        long v = intval(params, name);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    /** 列表页码：{@code intval(page) < 1 ? 1 : intval(page)}，上限 {@link #MAX_PAGE}。 */
    static int page(Map<String, PhpInput.Value> params) {
        long p = intval(params, "page");
        return (int) Math.min(MAX_PAGE, Math.max(1, p));
    }

    /** 列表页大小：{@code intval(limit) < min ? fallback : intval(limit)}，上限 {@link #MAX_LIMIT}。 */
    static int limit(Map<String, PhpInput.Value> params, int min, int fallback) {
        long l = intval(params, "limit");
        return (int) Math.min(MAX_LIMIT, l < min ? fallback : l);
    }

    /**
     * 删除参数（index/controller/User.php:698-703）：{@code explode(',', clean(ids))}，每项 {@code intval(abs($v))}，去重保持首次出现的顺序。
     *
     * @return 超过 {@link #MAX_IDS} 项时为 null
     */
    static Set<Long> ids(String cleaned) {
        Set<Long> out = new LinkedHashSet<>();
        for (String part : cleaned.split(",", -1)) {
            out.add(PhpNumbers.absIntval(part));
            if (out.size() > MAX_IDS) {
                return null;
            }
        }
        return out;
    }

    static long now(Clock clock) {
        return clock.instant().getEpochSecond();
    }

    /** 旧 {@code strtotime("today")}（时区 PRC）。 */
    static long todayStart(long now) {
        return LocalDate.ofInstant(Instant.ofEpochSecond(now), MemberAccountService.ZONE)
                .atStartOfDay(MemberAccountService.ZONE).toEpochSecond();
    }

    static String str(String s) {
        return s == null ? "" : s;
    }

    static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
