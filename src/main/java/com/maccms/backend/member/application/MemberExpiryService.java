package com.maccms.backend.member.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 会员组到期批量降级（B10，docs/architecture/19-b10-member-finance.md 第 4.14 节；旧 {@code User::expire}，
 * common/model/User.php:395-409）：{@code UPDATE mac_user SET group_id = 2 WHERE group_id > 2 AND user_end_time <= 现在}——等于现在也降级
 * （与 {@code checkLogin} 的 {@code <} 不同，B08 黄金 {@code expire} 段）。B14 的后台会员列表第 1 页调用 {@link #expireAll()}。
 *
 * <p>定时执行是部署参数 {@code maccms.member.expiry-sweep-minutes}（默认 0 = 不执行；旧版没有定时降级）。&gt; 0 时用一个守护线程按固定间隔执行，
 * 日志只记命中行数。可写数据源关闭时不执行。
 */
public class MemberExpiryService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MemberExpiryService.class);

    private final MemberFinanceWriteMapper writer;
    private final Clock clock;
    private final Duration interval;
    private ScheduledExecutorService executor;

    /**
     * @param writer   可写数据源关闭时为 null
     * @param interval 定时执行的间隔；为 null 或不是正数时不定时执行
     */
    public MemberExpiryService(MemberFinanceWriteMapper writer, Clock clock, Duration interval) {
        this.writer = writer;
        this.clock = clock;
        this.interval = interval;
    }

    /** 执行一次批量降级，返回命中（匹配）的行数；可写数据源关闭时为 0。 */
    public int expireAll() {
        if (writer == null) {
            return 0;
        }
        return writer.expireMembers(FinanceSupport.now(clock));
    }

    /** 定时执行是否打开。 */
    public boolean scheduled() {
        return writer != null && interval != null && !interval.isZero() && !interval.isNegative();
    }

    @Override
    public synchronized void start() {
        if (!scheduled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "member-expiry-sweep");
            t.setDaemon(true);
            return t;
        });
        long seconds = interval.toSeconds();
        executor.scheduleWithFixedDelay(this::sweep, seconds, seconds, TimeUnit.SECONDS);
        log.info("member expiry sweep scheduled every {} minutes", interval.toMinutes());
    }

    private void sweep() {
        try {
            int matched = expireAll();
            if (matched > 0) {
                log.info("member expiry sweep downgraded {} members", matched);
            }
        } catch (RuntimeException e) {
            log.warn("member expiry sweep failed: {}", SafeExceptionLog.typeChain(e));
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
