package com.maccms.backend.common.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.member.MemberGolden;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 进程内会话与旧 {@code mac_get_time_span} 语义（B08，17 文档第 2.3、2.5 节）。 */
class SessionStoreTest {

    @Test
    void timeSpanMatchesPhpGoldenForBothVersions() {
        for (String v : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            for (JsonNode c : MemberGolden.load(v).get("timespan")) {
                assertThat(c.get("same_second").asBoolean()).isTrue();
                assertThat(c.get("stored_is_now").asBoolean()).isTrue();
                SessionState s = SessionStore.withDefaults(new MutableClock(2_000_000_000L)).create();
                long now = 2_000_000_000L;
                JsonNode offset = c.get("offset");
                if (!offset.isNull()) {
                    s.timeSpan("last_searchtime", now - offset.asLong());
                    assertThat(s.timeSpan("last_searchtime", now)).isEqualTo(c.get("span").asLong());
                } else {
                    // 没有记录时按 1228348800 计算（旧版 common.php:311）
                    assertThat(s.timeSpan("last_searchtime", now)).isEqualTo(now - 1228348800L);
                    assertThat(c.get("span").asLong()).isGreaterThan(500_000_000L);
                }
                // 无论结果如何都把记录改成当前时刻
                assertThat(s.timeSpan("last_searchtime", now)).isZero();
            }
        }
    }

    @Test
    void rejectedCallsAlsoRefreshTheTimerAndRecoveryNeedsAFullQuietWindow() {
        long t = 2_000_000_000L;
        SessionState s = SessionStore.withDefaults(new MutableClock(t)).create();
        long timespan = 3;
        assertThat(s.timeSpan("k", t)).isGreaterThanOrEqualTo(timespan);
        assertThat(s.timeSpan("k", t + 2)).isLessThan(timespan);
        // 第二次被拒也刷新了计时：t+4 距 t+2 只有 2 秒，仍被拒
        assertThat(s.timeSpan("k", t + 4)).isLessThan(timespan);
        // 停下 3 秒后恢复（等于间隔即放行：旧版是 span < timespan 才拒绝）
        assertThat(s.timeSpan("k", t + 7)).isEqualTo(3).isGreaterThanOrEqualTo(timespan);
    }

    @Test
    void concurrentCallsOnOneSessionLetExactlyOneThrough() throws Exception {
        SessionState s = SessionStore.withDefaults(new MutableClock(2_000_000_000L)).create();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> spans = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                spans.add(pool.submit(() -> {
                    start.await();
                    return s.timeSpan("last_searchtime", 2_000_000_000L);
                }));
            }
            start.countDown();
            int passed = 0;
            for (Future<Long> f : spans) {
                if (f.get() >= 3) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknownMalformedAndIdleSessionsAreNotFound() {
        MutableClock clock = new MutableClock(10_000);
        SessionStore store = new SessionStore(clock, Duration.ofSeconds(1800), 10);
        SessionState s = store.create();
        assertThat(s.id()).matches("[0-9a-f]{32}");
        assertThat(store.find(s.id())).containsSame(s);
        assertThat(store.find(s.id().toUpperCase())).isEmpty();
        assertThat(store.find("0123456789abcdef0123456789abcdef")).isEmpty();
        assertThat(store.find(null)).isEmpty();
        assertThat(store.find("../x")).isEmpty();
        clock.advanceSeconds(1800);
        assertThat(store.find(s.id())).as("刚好 1800 秒仍有效，并刷新").containsSame(s);
        clock.advanceSeconds(1801);
        assertThat(store.find(s.id())).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void storeIsBoundedAndEvictsLeastRecentlyUsed() {
        MutableClock clock = new MutableClock(10_000);
        SessionStore store = new SessionStore(clock, Duration.ofSeconds(1800), 3);
        SessionState a = store.create();
        SessionState b = store.create();
        SessionState c = store.create();
        store.find(a.id());
        SessionState d = store.create();
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.find(b.id())).as("最久未访问的被淘汰").isEmpty();
        assertThat(store.find(a.id())).isPresent();
        assertThat(store.find(c.id())).isPresent();
        assertThat(store.find(d.id())).isPresent();
        assertThat(a.id()).isNotEqualTo(c.id()).isNotEqualTo(d.id());
    }
}
