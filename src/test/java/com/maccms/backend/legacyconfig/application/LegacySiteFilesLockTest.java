package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C-73 {@code LegacySiteFiles.tryExclusiveLock}：同进程内的互斥、路径校验、锁文件只建不删（跨进程见 {@code DatabaseRunsLockIT}）。 */
class LegacySiteFilesLockTest {

    private static final String LOCK = "application/data/backup/database/dbtool.lock";

    @TempDir
    Path site;

    private LegacySiteFiles files() throws Exception {
        Files.createDirectories(site.resolve("application"));
        return new LegacySiteFiles(new LegacyConfigProperties(site.resolve("application").toString()));
    }

    @Test
    void secondHolderIsRefusedUntilTheFirstCloses() throws Exception {
        LegacySiteFiles files = files();
        Optional<LegacySiteFiles.ExclusiveLock> first = files.tryExclusiveLock(LOCK);
        assertThat(first).isPresent();
        assertThat(Files.isRegularFile(site.resolve(LOCK))).isTrue();
        // 同一 JVM 的另一个 LegacySiteFiles 实例同样拿不到（进程内锁按规范化路径登记）
        assertThat(files().tryExclusiveLock(LOCK)).isEmpty();
        assertThat(files.tryExclusiveLock(LOCK)).isEmpty();
        first.get().close();
        first.get().close(); // 重复关闭无副作用
        Optional<LegacySiteFiles.ExclusiveLock> again = files.tryExclusiveLock(LOCK);
        assertThat(again).isPresent();
        again.get().close();
        // 锁文件只创建、不删除
        assertThat(Files.isRegularFile(site.resolve(LOCK))).isTrue();
    }

    @Test
    void manyThreadsRacingOnlyOneWins() throws Exception {
        LegacySiteFiles files = files();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 20; round++) {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch held = new CountDownLatch(threads);
                AtomicInteger winners = new AtomicInteger();
                Future<?>[] fs = new Future<?>[threads];
                for (int i = 0; i < threads; i++) {
                    fs[i] = pool.submit(() -> {
                        start.await();
                        Optional<LegacySiteFiles.ExclusiveLock> l = files.tryExclusiveLock(LOCK);
                        if (l.isPresent()) {
                            winners.incrementAndGet();
                        }
                        held.countDown();
                        held.await(5, TimeUnit.SECONDS);
                        l.ifPresent(LegacySiteFiles.ExclusiveLock::close);
                        return null;
                    });
                }
                start.countDown();
                for (Future<?> f : fs) {
                    f.get(10, TimeUnit.SECONDS);
                }
                assertThat(winners.get()).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockPathMustBeInsideAWritableArea() throws Exception {
        LegacySiteFiles files = files();
        assertThatThrownBy(() -> files.tryExclusiveLock("application/extra/x.lock")).isInstanceOf(LegacySiteFiles.FileException.class);
        assertThatThrownBy(() -> files.tryExclusiveLock("application/data/backup/../../x.lock"))
                .isInstanceOf(LegacySiteFiles.FileException.class);
        LegacySiteFiles none = new LegacySiteFiles(new LegacyConfigProperties(""));
        assertThatThrownBy(() -> none.tryExclusiveLock(LOCK)).isInstanceOf(LegacySiteFiles.FileException.class);
    }
}
