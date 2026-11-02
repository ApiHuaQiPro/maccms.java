package com.maccms.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.DatabaseRun;
import com.maccms.backend.admin.application.DatabaseRun.State;
import com.maccms.backend.admin.application.DatabaseRuns;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C-74 跨进程互斥与被杀后的中断判定（V-31 ②、④、⑪、⑫、⑮）：子进程是 {@link DatabaseRunsLockProbe}。不连数据库，可单独运行：
 * {@code ./mvnw -B test -Dtest=DatabaseRunsLockIT -Dsurefire.failIfNoSpecifiedTests=false}。
 */
class DatabaseRunsLockIT {

    @TempDir
    Path site;

    private LegacySiteFiles files;
    private DatabaseRuns runs;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(site.resolve("application"));
        files = new LegacySiteFiles(new LegacyConfigProperties(site.resolve("application").toString()));
        runs = new DatabaseRuns(files, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    private Process child(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(DatabaseRunsLockProbe.class.getName());
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(false).start();
    }

    /** 子进程的第一个协议行（LOCKED / GOT / BUSY）；日志也写到标准输出，跳过。 */
    private static String firstLine(Process p) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            String t = line.strip();
            if (t.equals("LOCKED") || t.equals("GOT") || t.equals("BUSY")) {
                return t;
            }
        }
        return "";
    }

    private DatabaseRun killHolder(String op, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("hold", site.resolve("application").toString(), op));
        args.addAll(List.of(extra));
        Process p = child(args.toArray(String[]::new));
        try {
            assertThat(firstLine(p)).isEqualTo("LOCKED");
            // 持锁进程活着：本进程拿不到锁，页面显示“执行中”且不改写记录
            assertThat(runs.open()).isEmpty();
            assertThat(runs.probe().busy()).isTrue();
            assertThat(runs.read().state).isEqualTo(State.RUNNING);
        } finally {
            p.destroyForcibly();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
        // 进程被杀后由操作系统释放锁；Windows 上释放可能稍有延迟（文档行为），最多等 10 秒
        DatabaseRuns.Probe after = runs.probe();
        for (int i = 0; i < 100 && after.busy(); i++) {
            Thread.sleep(100);
            after = runs.probe();
        }
        assertThat(after.busy()).isFalse();
        return after.record();
    }

    @Test
    void killedSqlHolderIsJudgedInterruptedAndPending() throws Exception {
        DatabaseRun r = killHolder("SQL");
        assertThat(r.state).isEqualTo(State.INTERRUPTED);
        assertThat(r.pending()).isTrue();
        assertThat(r.interrupted.fromState).isEqualTo(State.RUNNING);
    }

    @Test
    void killedOptimizeHolderIsJudgedDone() throws Exception {
        DatabaseRun r = killHolder("OPTIMIZE");
        assertThat(r.state).isEqualTo(State.DONE);
        assertThat(r.outcome).isEqualTo("INTERRUPTED");
        assertThat(r.pending()).isFalse();
    }

    @Test
    void killedExportHolderIsJudgedDoneAndHalfPartsAreRemoved() throws Exception {
        String part = DatabaseRuns.DIR + "/20261101-000000-1.sql";
        DatabaseRun r = killHolder("EXPORT", part);
        assertThat(r.state).isEqualTo(State.DONE);
        assertThat(r.outcome).isEqualTo("INTERRUPTED");
        assertThat(r.interrupted.cleanup).isEqualTo("OK");
        assertThat(site.resolve(part)).doesNotExist();
        assertThat(site.resolve(DatabaseRuns.DIR + "/backup.lock")).doesNotExist();
        assertThat(site.resolve(DatabaseRuns.LOCK)).exists();
    }

    @Test
    void twoProcessesStartingTogetherOnlyOneGetsTheLock() throws Exception {
        // 两个子进程同时启动，各持锁 8 秒：JVM 启动相差远小于 8 秒，所以两者的取锁时刻一定落在对方的持锁期内，只能有一个 GOT
        for (int round = 0; round < 3; round++) {
            Process a = child("race", site.resolve("application").toString(), "8000");
            Process b = child("race", site.resolve("application").toString(), "8000");
            String ra = firstLine(a);
            String rb = firstLine(b);
            assertThat(a.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(b.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(List.of(ra, rb)).as("round %d", round).containsExactlyInAnyOrder("GOT", "BUSY");
        }
    }
}
