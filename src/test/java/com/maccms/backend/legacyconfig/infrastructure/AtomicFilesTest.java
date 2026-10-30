package com.maccms.backend.legacyconfig.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 原子写（B15，24 文档第 3 节）：同目录临时文件 + 原子改名，读到的修改前版本（SHA-256）与当前不同则拒绝（两个管理员并发保存时后一方失败），
 * 读者任何时候只会读到完整的旧内容或完整的新内容，失败不留临时文件。
 */
class AtomicFilesTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> leftovers() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp")).toList();
        }
    }

    @Test
    void createReplaceAndRejectStaleVersions() throws Exception {
        Path f = dir.resolve("maccms.php");
        assertThat(AtomicFiles.currentVersion(f)).isEqualTo(AtomicFiles.ABSENT);
        AtomicFiles.replace(f, b("v1"), AtomicFiles.ABSENT);
        assertThat(Files.readString(f)).isEqualTo("v1");
        String v1 = AtomicFiles.currentVersion(f);
        assertThat(v1).isEqualTo(AtomicFiles.version(b("v1"))).hasSize(64);

        AtomicFiles.replace(f, b("v2"), v1);
        assertThat(Files.readString(f)).isEqualTo("v2");
        // 基于旧版本的保存（他人已改过）被拒绝，文件不变
        assertThatThrownBy(() -> AtomicFiles.replace(f, b("v3"), v1)).isInstanceOf(AtomicFiles.WriteException.class)
                .satisfies(e -> assertThat(((AtomicFiles.WriteException) e).failure()).isEqualTo(AtomicFiles.Failure.CONFLICT));
        assertThatThrownBy(() -> AtomicFiles.replace(f, b("v3"), AtomicFiles.ABSENT)).isInstanceOf(AtomicFiles.WriteException.class);
        assertThat(Files.readString(f)).isEqualTo("v2");
        // 不检查版本时照写
        AtomicFiles.replace(f, b("v4"), null);
        assertThat(Files.readString(f)).isEqualTo("v4");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void writeFailureLeavesTheOriginalAndNoTemporaryFile() throws Exception {
        Path f = dir.resolve("x.php");
        Files.writeString(f, "old");
        // 目标是目录：改名失败，原内容与目录都不受影响，临时文件已清理
        Path d = Files.createDirectories(dir.resolve("sub.php"));
        Files.writeString(d.resolve("keep"), "k");
        assertThatThrownBy(() -> AtomicFiles.replace(d, b("new"), null)).isInstanceOf(AtomicFiles.WriteException.class);
        assertThat(Files.readString(d.resolve("keep"))).isEqualTo("k");
        assertThat(Files.readString(f)).isEqualTo("old");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void concurrentSavesFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        Path f = dir.resolve("vodplayer.php");
        AtomicFiles.replace(f, b("base"), null);
        String base = AtomicFiles.currentVersion(f);
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String content = "admin-" + i;
            results.add(pool.submit(() -> {
                go.await();
                try {
                    AtomicFiles.replace(f, b(content), base);
                    return true;
                } catch (AtomicFiles.WriteException e) {
                    assertThat(e.failure()).isEqualTo(AtomicFiles.Failure.CONFLICT);
                    return false;
                }
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> r : results) {
            wins += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(Files.readString(f)).startsWith("admin-");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void readersOnlyEverSeeACompleteVersion() throws Exception {
        Path f = dir.resolve("big.php");
        byte[] a = new byte[512 * 1024];
        byte[] z = new byte[512 * 1024];
        Arrays.fill(a, (byte) 'A');
        Arrays.fill(z, (byte) 'Z');
        AtomicFiles.replace(f, a, null);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger torn = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            readers.add(pool.submit(() -> {
                while (!done.get()) {
                    try {
                        byte[] got = Files.readAllBytes(f);
                        reads.incrementAndGet();
                        if (got.length != a.length || !(Arrays.equals(got, a) || Arrays.equals(got, z))) {
                            torn.incrementAndGet();
                        }
                    } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException e) {
                        // Windows 上改名的瞬间可能短暂打不开，但不会读到半个文件
                    }
                }
                return null;
            }));
        }
        for (int i = 0; i < 40; i++) {
            AtomicFiles.replace(f, i % 2 == 0 ? z : a, null);
        }
        done.set(true);
        for (Future<?> r : readers) {
            r.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(reads.get()).isPositive();
        assertThat(torn.get()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void lockedSerializesReadModifyWriteOnTheSameFile() throws Exception {
        Path f = dir.resolve("counter.php");
        AtomicFiles.replace(f, b("0"), null);
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(pool.submit(() -> AtomicFiles.locked(f, () -> {
                try {
                    int v = Integer.parseInt(Files.readString(f));
                    Thread.sleep(2);
                    AtomicFiles.replace(f, b(Integer.toString(v + 1)), AtomicFiles.currentVersion(f));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            })));
        }
        for (Future<?> r : all) {
            r.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(Files.readString(f)).isEqualTo(Integer.toString(n));
    }
}
