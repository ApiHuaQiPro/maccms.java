package com.maccms.backend.content.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.view.CounterOutcome;
import com.maccms.backend.content.application.view.DiggCounts;
import com.maccms.backend.content.application.view.HitCounts;
import com.maccms.backend.content.application.view.RefererCounts;
import com.maccms.backend.content.application.view.ScoreCounts;
import com.maccms.backend.content.infrastructure.ContentCounterMapper;
import com.maccms.backend.content.infrastructure.ContentCounterWriteMapper;
import com.maccms.backend.content.infrastructure.CounterRow;
import com.maccms.backend.content.infrastructure.HitPeriods;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 内容计数（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 5 节）：点击（含日/周/月）、顶踩、评分、网址来路、Provide
 * 的 {@code detail_inc_hits}。参数校验、cookie 与 JSON 由调用方（site、openapi）按旧控制器处理，这里只做按主键的读写。
 *
 * <p>与旧版相同：不看审核状态与分类（旧 {@code infoData} 只按主键）；区别：计数是单条原子 UPDATE（旧版先读后写，并发时丢计数），写入后
 * 重读返回当前值（顺序请求时与旧版算出的值相同；并发时是写入后的实际值）。写入被数据库拒绝（列越界）时返回
 * {@link CounterOutcome.Status#REJECTED}，没有任何写入（旧版同样没有写入，但返回 500）。
 */
public class ContentCounterService {

    /** 旧版计算日/周/月所用的时区（{@code date_default_timezone_set('PRC')}）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final Logger log = LoggerFactory.getLogger(ContentCounterService.class);

    private final ContentCounterMapper reader;
    private final ContentCounterWriteMapper writer;
    private final Clock clock;

    /**
     * @param writer 可写数据源关闭时为 null：只读操作照常，写操作抛 {@link IllegalStateException}（调用方先看 {@link #writable()}）
     */
    public ContentCounterService(ContentCounterMapper reader, ContentCounterWriteMapper writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    public boolean writable() {
        return writer != null;
    }

    public long now() {
        return clock.instant().getEpochSecond();
    }

    // ------------------------------------------------------------------ 点击

    /** 只读点击数（旧 {@code ajax/hits} 的 type 不是 update，Ajax.php:210-215）。 */
    public CounterOutcome<HitCounts> hits(CounterTarget target, long id) {
        CounterRow row = reader.selectCounters(target.name(), id);
        return row == null ? CounterOutcome.missing() : CounterOutcome.found(hits(row));
    }

    /** 点击一次（Ajax.php:172-209）：内容不存在时什么都不写（旧版先 infoData，不存在即"获取失败"）。 */
    public CounterOutcome<HitCounts> hit(CounterTarget target, long id) {
        HitPeriods p = periods(now());
        CounterOutcome<Void> w = write(target, () -> switch (target) {
            case VOD -> writer().hitVod(id, p);
            case ART -> writer().hitArt(id, p);
            case TOPIC -> writer().hitTopic(id, p);
            case ACTOR -> writer().hitActor(id, p);
            case ROLE -> writer().hitRole(id, p);
            case WEBSITE -> writer().hitWebsite(id, p);
        });
        return w.status() == CounterOutcome.Status.REJECTED ? CounterOutcome.rejected() : hits(target, id);
    }

    // ------------------------------------------------------------------ 顶踩

    /** 只读顶踩数（type 为空时，Ajax.php:331-344）。 */
    public CounterOutcome<DiggCounts> digg(CounterTarget target, long id) {
        CounterRow row = reader.selectCounters(target.name(), id);
        return row == null ? CounterOutcome.missing() : CounterOutcome.found(digg(row));
    }

    /**
     * 顶或踩一次再读（Ajax.php:322-344：先 setInc 再 infoData，内容不存在时 setInc 不命中、之后"获取失败"）。
     *
     * @param up true 顶，false 踩
     */
    public CounterOutcome<DiggCounts> digg(CounterTarget target, long id, boolean up) {
        CounterOutcome<Void> w = write(target, () -> switch (target) {
            case VOD -> writer().diggVod(id, up);
            case ART -> writer().diggArt(id, up);
            case TOPIC -> writer().diggTopic(id, up);
            case ACTOR -> writer().diggActor(id, up);
            case ROLE -> writer().diggRole(id, up);
            case WEBSITE -> writer().diggWebsite(id, up);
        });
        return w.status() == CounterOutcome.Status.REJECTED ? CounterOutcome.rejected() : digg(target, id);
    }

    // ------------------------------------------------------------------ 评分

    /** 只读评分（score 为 0 时，Ajax.php:387-391）。 */
    public CounterOutcome<ScoreCounts> score(CounterTarget target, long id) {
        CounterRow row = reader.selectCounters(target.name(), id);
        return row == null ? CounterOutcome.missing() : CounterOutcome.found(score(row));
    }

    /**
     * 评分一次再读（Ajax.php:375-386）。{@code score} 是旧版 {@code mac_param_url} 的 {@code intval}，可以是负数或很大的数：
     * 总分或平均分越界时数据库拒绝（旧版 500），返回 {@link CounterOutcome.Status#REJECTED}。
     */
    public CounterOutcome<ScoreCounts> score(CounterTarget target, long id, long score) {
        CounterOutcome<Void> w = write(target, () -> switch (target) {
            case VOD -> writer().scoreVod(id, score);
            case ART -> writer().scoreArt(id, score);
            case TOPIC -> writer().scoreTopic(id, score);
            case ACTOR -> writer().scoreActor(id, score);
            case ROLE -> writer().scoreRole(id, score);
            case WEBSITE -> writer().scoreWebsite(id, score);
        });
        return w.status() == CounterOutcome.Status.REJECTED ? CounterOutcome.rejected() : score(target, id);
    }

    // ------------------------------------------------------------------ 网址来路

    /**
     * 按域名找来路网址（Ajax.php:237-245）：{@code website_jumpurl} 以 {@code http://<domain>} 或 {@code https://<domain>} 开头的第一行
     * （按主键）。域名按字面量匹配（旧版 % 与 _ 是 LIKE 通配符，D-B11-6）。
     */
    public CounterOutcome<RefererCounts> refererSite(String domain) {
        CounterRow row = reader.selectRefererSite(prefixPattern("http://", domain), prefixPattern("https://", domain));
        return row == null ? CounterOutcome.missing() : CounterOutcome.found(referer(row));
    }

    /** 来路计数一次（Ajax.php:255-292）：匹配的网址各自 +1（D-B11-7），返回第一行的当前值。 */
    public CounterOutcome<RefererCounts> referer(String domain) {
        HitPeriods p = periods(now());
        String http = prefixPattern("http://", domain);
        String https = prefixPattern("https://", domain);
        CounterOutcome<Void> w = write(CounterTarget.WEBSITE, () -> writer().refererWebsite(http, https, p));
        return w.status() == CounterOutcome.Status.REJECTED ? CounterOutcome.rejected() : refererSite(domain);
    }

    // ------------------------------------------------------------------ Provide

    /**
     * Provide {@code ac=detail} 单个 ID 的点击 +1（api/controller/Provide.php:142-150）：不看视频是否存在、是否审核（不存在时不命中）。
     *
     * @return 写入被拒绝（点击数已到上限）时为 false
     */
    public boolean provideDetailHit(long vodId) {
        return write(CounterTarget.VOD, () -> writer().provideHitVod(vodId)).status() != CounterOutcome.Status.REJECTED;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 旧 {@code Ajax::hits} 的日/周/月区间（Ajax.php:178-199，PRC）：同年同月、本周日 0 点至周六 23:59:59（含两端）、同年同月同日。
     */
    public static HitPeriods periods(long now) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochSecond(now), ZONE);
        long dayStart = today.atStartOfDay(ZONE).toEpochSecond();
        long dayEnd = today.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        // PHP getdate()['wday']：0 = 星期日 … 6 = 星期六
        int wday = today.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : today.getDayOfWeek().getValue();
        // 与旧版同样按"今天的 0 点 / 23:59:59 加减整天秒数"计算（夏令时历史日期上也与 mktime 相同）
        long weekStart = dayStart - wday * 86_400L;
        long weekEnd = today.atTime(23, 59, 59).atZone(ZONE).toEpochSecond() + (6 - wday) * 86_400L;
        long monthStart = today.withDayOfMonth(1).atStartOfDay(ZONE).toEpochSecond();
        long monthEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toEpochSecond();
        return new HitPeriods(dayStart, dayEnd, weekStart, weekEnd, monthStart, monthEnd, now);
    }

    /** {@code <scheme><domain>%}，域名中的 {@code ! % _} 按 {@code ESCAPE '!'} 转义。 */
    static String prefixPattern(String scheme, String domain) {
        StringBuilder sb = new StringBuilder(scheme.length() + domain.length() + 4).append(scheme);
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    private CounterOutcome<Void> write(CounterTarget target, IntSupplier statement) {
        try {
            statement.getAsInt();
            return CounterOutcome.found(null);
        } catch (DataAccessException e) {
            // 列越界（mediumint unsigned 上限、decimal(3,1)）等被严格模式拒绝：整条语句不生效
            log.warn("content counter write rejected: target={} cause={}", target, SafeExceptionLog.typeChain(e));
            return CounterOutcome.rejected();
        }
    }

    private ContentCounterWriteMapper writer() {
        if (writer == null) {
            throw new IllegalStateException("可写数据源未打开");
        }
        return writer;
    }

    private static HitCounts hits(CounterRow r) {
        return new HitCounts(nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()));
    }

    private static DiggCounts digg(CounterRow r) {
        return new DiggCounts(nz(r.up()), nz(r.down()));
    }

    private static ScoreCounts score(CounterRow r) {
        String s = r.score() == null ? "0.0" : r.score().setScale(1, java.math.RoundingMode.UNNECESSARY).toPlainString();
        return new ScoreCounts(s, nz(r.scoreNum()), nz(r.scoreAll()));
    }

    private static RefererCounts referer(CounterRow r) {
        return new RefererCounts(nz(r.referer()), nz(r.refererDay()), nz(r.refererWeek()), nz(r.refererMonth()));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
