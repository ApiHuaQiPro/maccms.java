package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.DatabaseRun.Op;
import com.maccms.backend.admin.application.DatabaseRun.Phase;
import com.maccms.backend.admin.application.DatabaseRun.State;
import com.maccms.backend.admin.application.DatabaseRuns.AckResult;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C-74 {@code DatabaseRunsTest}：V-31 ①、③、④、⑪～⑯、⑲ 的单进程部分（跨进程见 {@code DatabaseRunsLockIT}）。 */
class DatabaseRunsTest {

    private static final String DIR = DatabaseRuns.DIR;

    @TempDir
    Path site;

    private LegacySiteFiles files;
    private DatabaseRuns runs;
    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(site.resolve("application"));
        files = new LegacySiteFiles(new LegacyConfigProperties(site.resolve("application").toString()));
        runs = new DatabaseRuns(files, Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                () -> "run-" + seq.incrementAndGet());
    }

    private DatabaseRun begin(Op op) {
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            return s.begin(op, "1", 3);
        }
    }

    private DatabaseRun write(DatabaseRun r) {
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            s.write(r);
            return r;
        }
    }

    @Test
    void lockIsExclusiveAndSecondOpenIsRefusedWithoutTouchingTheRecord() {
        DatabaseRuns.Session first = runs.open().orElseThrow();
        DatabaseRun r = first.begin(Op.SQL, "1", 1);
        assertThat(runs.open()).isEmpty();
        // 页面加载：拿不到锁 → 执行中，不改写记录（V-31 ②④ 的单进程部分）
        assertThat(runs.probe().busy()).isTrue();
        assertThat(runs.read().state).isEqualTo(State.RUNNING);
        r.state = State.DONE;
        r.outcome = "OK";
        first.write(r);
        first.close();
        assertThat(runs.probe().busy()).isFalse();
        assertThat(runs.read().outcome).isEqualTo("OK");
    }

    @Test
    void recordRoundTripsAsSnakeCaseJsonWithoutSqlText() throws Exception {
        DatabaseRun r = begin(Op.IMPORT);
        String json = Files.readString(site.resolve(DatabaseRuns.RECORD), StandardCharsets.UTF_8);
        assertThat(json).contains("\"admin_id\":\"1\"", "\"state\":\"RUNNING\"", "\"op\":\"IMPORT\"", "\"started_at\"");
        assertThat(runs.read().run).isEqualTo(r.run);
    }

    @Test
    void optimizeInterruptedBecomesDoneWithWarn() {
        DatabaseRun r = begin(Op.OPTIMIZE);
        r.tablesDone = 2;
        write(r);
        DatabaseRun after = runs.probe().record();
        assertThat(after.state).isEqualTo(State.DONE);
        assertThat(after.outcome).isEqualTo("INTERRUPTED");
        assertThat(after.tablesDone).isEqualTo(2);
        assertThat(after.pending()).isFalse();
        assertThat(after.interrupted.fromState).isEqualTo(State.RUNNING);
        assertThat(runs.interrupt(running(Op.REPAIR, null)).level()).isEqualTo(org.slf4j.event.Level.WARN);
    }

    @Test
    void sqlInterruptedBecomesPendingInterruptedWithError() {
        begin(Op.SQL);
        DatabaseRun after = runs.probe().record();
        assertThat(after.state).isEqualTo(State.INTERRUPTED);
        assertThat(after.pending()).isTrue();
        assertThat(runs.interrupt(running(Op.SQL, null)).level()).isEqualTo(org.slf4j.event.Level.ERROR);
    }

    @Test
    void importInterruptedDuringAutoBackupCleansOnlyNamedPartsAndOwnLock() throws Exception {
        Files.createDirectories(site.resolve(DIR));
        Files.writeString(site.resolve(DIR + "/20261101-000000-1.sql"), "half");
        Files.writeString(site.resolve(DIR + "/20261101-000000-2.sql"), "half");
        Files.writeString(site.resolve(DIR + "/20250101-000000-1.sql"), "other backup");
        Files.writeString(site.resolve(DIR + "/backup.lock"), "1761955200");
        DatabaseRun r = begin(Op.IMPORT);
        r.phase = Phase.AUTO_BACKUP;
        r.exportDir = DIR;
        r.exportFiles.add(DIR + "/20261101-000000-1.sql");
        r.exportFiles.add(DIR + "/20261101-000000-2.sql");
        r.backupLock = "1761955200";
        write(r);
        DatabaseRun after = runs.probe().record();
        assertThat(after.state).isEqualTo(State.DONE);
        assertThat(after.outcome).isEqualTo("INTERRUPTED_BEFORE_WRITE");
        assertThat(after.interrupted.cleanup).isEqualTo("OK");
        assertThat(after.interrupted.fromPhase).isEqualTo(Phase.AUTO_BACKUP);
        assertThat(site.resolve(DIR + "/20261101-000000-1.sql")).doesNotExist();
        assertThat(site.resolve(DIR + "/20261101-000000-2.sql")).doesNotExist();
        assertThat(site.resolve(DIR + "/backup.lock")).doesNotExist();
        assertThat(site.resolve(DIR + "/20250101-000000-1.sql")).exists();
    }

    @Test
    void exportInterruptedKeepsAForeignBackupLock() throws Exception {
        Files.createDirectories(site.resolve(DIR));
        Files.writeString(site.resolve(DIR + "/20261101-000000-1.sql.gz"), "half");
        Files.writeString(site.resolve(DIR + "/backup.lock"), "999");
        DatabaseRun r = begin(Op.EXPORT);
        r.exportDir = DIR;
        r.exportFiles.add(DIR + "/20261101-000000-1.sql.gz");
        r.backupLock = "1761955200";
        write(r);
        DatabaseRun after = runs.probe().record();
        assertThat(after.state).isEqualTo(State.DONE);
        assertThat(after.outcome).isEqualTo("INTERRUPTED");
        assertThat(after.interrupted.cleanup).isEqualTo("LOCK_KEPT");
        assertThat(after.interrupted.leftovers).containsExactly(DIR + "/backup.lock");
        assertThat(site.resolve(DIR + "/backup.lock")).exists();
        assertThat(site.resolve(DIR + "/20261101-000000-1.sql.gz")).doesNotExist();
        assertThat(runs.interrupt(running(Op.EXPORT, null)).level()).isEqualTo(org.slf4j.event.Level.WARN);
    }

    @Test
    void cleanupNeverDeletesFilesThatAreNotBackupParts() throws Exception {
        Files.createDirectories(site.resolve(DIR));
        Files.writeString(site.resolve(DIR + "/keep.txt"), "x");
        DatabaseRun r = begin(Op.EXPORT);
        r.exportFiles.add(DIR + "/keep.txt");
        write(r);
        DatabaseRun after = runs.probe().record();
        assertThat(after.interrupted.cleanup).isEqualTo("FAILED");
        assertThat(site.resolve(DIR + "/keep.txt")).exists();
    }

    @Test
    void importInterruptedDuringRestoreAndRecoveringStayPending() {
        DatabaseRun r = begin(Op.IMPORT);
        r.phase = Phase.RESTORE;
        r.exportFiles.add(DIR + "/20261101-000000-1.sql");
        write(r);
        DatabaseRun after = runs.probe().record();
        assertThat(after.state).isEqualTo(State.INTERRUPTED);
        assertThat(after.interrupted.cleanup).isEqualTo("NONE");

        after.state = State.RECOVERING;
        after.recovery = new DatabaseRun.Recovery();
        after.recovery.attempt = 2;
        write(after);
        DatabaseRun rec = runs.probe().record();
        assertThat(rec.state).isEqualTo(State.RECOVERY_INTERRUPTED);
        assertThat(rec.recovery.attempt).isEqualTo(2);
        assertThat(rec.recovery.lastResult).isEqualTo("INTERRUPTED");
    }

    @Test
    void judgementIsIdempotent() throws Exception {
        begin(Op.SQL);
        runs.probe();
        String once = Files.readString(site.resolve(DatabaseRuns.RECORD));
        runs.probe();
        runs.probe();
        assertThat(Files.readString(site.resolve(DatabaseRuns.RECORD))).isEqualTo(once);
    }

    @Test
    void pendingRecordBlocksNewOperations() {
        begin(Op.SQL);
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            assertThat(s.current().state).isEqualTo(State.INTERRUPTED);
            assertThat(s.blocksNewOperation()).isTrue();
        }
    }

    @Test
    void acknowledgeFollowsTheStateMachine() {
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            assertThat(runs.acknowledge(s, "x", true, "1")).isEqualTo(AckResult.NOT_PENDING);
        }
        DatabaseRun r = begin(Op.SQL);
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            assertThat(runs.acknowledge(s, "other", true, "1")).isEqualTo(AckResult.RUN_MISMATCH);
            assertThat(runs.acknowledge(s, r.run, false, "1")).isEqualTo(AckResult.CONFIRM_REQUIRED);
            assertThat(s.current().state).isEqualTo(State.INTERRUPTED);
            assertThat(runs.acknowledge(s, r.run, true, "7")).isEqualTo(AckResult.OK);
            assertThat(runs.acknowledge(s, r.run, true, "7")).isEqualTo(AckResult.NOT_PENDING);
        }
        DatabaseRun done = runs.read();
        assertThat(done.state).isEqualTo(State.DONE);
        assertThat(done.ack.acceptedAsIs).isTrue();
        assertThat(done.ack.adminId).isEqualTo("7");

        DatabaseRun recovered = begin(Op.IMPORT);
        recovered.state = State.RECOVERED;
        write(recovered);
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            assertThat(s.blocksNewOperation()).isTrue();
            assertThat(runs.acknowledge(s, recovered.run, false, "1")).isEqualTo(AckResult.OK);
            assertThat(s.blocksNewOperation()).isFalse();
        }
        assertThat(runs.read().ack.acceptedAsIs).isFalse();
    }

    private static DatabaseRun running(Op op, Phase phase) {
        DatabaseRun r = new DatabaseRun();
        r.run = "r";
        r.op = op;
        r.phase = phase;
        r.state = State.RUNNING;
        return r;
    }
}
