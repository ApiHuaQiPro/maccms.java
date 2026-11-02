package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.DatabaseRun.Op;
import com.maccms.backend.admin.application.DatabaseRun.State;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.infrastructure.DatabaseToolMapper;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DBT-4（技术设计 v13 §6.13、C-53）：导出与删备份在 {@code dbtool.lock} 内执行；契约 API-015、API-016；V-31 ⑮、⑰ 的单进程部分。 */
class DatabaseToolLockedFilesTest {

    private static final String DIR = "application/data/backup/database";
    /** 2025-11-01 08:00:00 +08:00 */
    private static final long NOW = 1761955200L;
    private static final String STAMP = "20251101-080000";

    @TempDir
    Path site;

    private LegacySiteFiles files;
    private DatabaseRuns runs;
    private DatabaseToolService db;
    private final LegacyLang lang = LegacyLang.of("zh-cn");
    private final List<String> calls = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(site.resolve("application/extra"));
        Files.createDirectories(site.resolve(DIR));
        Files.writeString(site.resolve("application/extra/maccms.php"), """
                <?php
                return array (
                  'db' =>\s
                  array (
                    'backup_path' => './application/data/backup/database/',
                    'part_size' => '20971520',
                    'compress' => '0',
                    'compress_level' => '4',
                  ),
                );""", StandardCharsets.UTF_8);
        LegacyConfigProperties props = new LegacyConfigProperties(site.resolve("application").toString());
        files = new LegacySiteFiles(props);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        runs = new DatabaseRuns(files, clock, () -> "run-" + seq.incrementAndGet());
        db = new DatabaseToolService(new FakeReader(), null, new LegacyConfigService(props), files, clock,
                new DatabaseToolService.Endpoint("127.0.0.1", "3306", "maccms"), runs);
    }

    private final class FakeReader implements DatabaseToolMapper {
        @Override
        public List<LinkedHashMap<String, Object>> tableStatus() {
            LinkedHashMap<String, Object> a = new LinkedHashMap<>();
            a.put("Name", "mac_link");
            LinkedHashMap<String, Object> b = new LinkedHashMap<>();
            b.put("Name", "mac_other");
            return List.of(a, b);
        }

        @Override
        public List<LinkedHashMap<String, Object>> columns(String table) {
            LinkedHashMap<String, Object> c = new LinkedHashMap<>();
            c.put("Field", "link_id");
            return List.of(c);
        }

        @Override
        public LinkedHashMap<String, Object> createTable(String table) {
            calls.add("create:" + table);
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("Create Table", "CREATE TABLE `" + table + "` (`link_id` int)");
            return m;
        }

        @Override
        public long count(String table) {
            return 1;
        }

        @Override
        public List<LinkedHashMap<String, Object>> rows(String table, String columns, long offset) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("link_id", "7");
            return List.of(r);
        }
    }

    private List<String> backupFiles() throws Exception {
        try (Stream<Path> s = Files.list(site.resolve(DIR))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.contains(".sql")).sorted().toList();
        }
    }

    @Test
    void exportRunsInsideTheLockAndRecordsItsFiles() throws Exception {
        Reply r = db.export(List.of("mac_link"), "0", lang, "1");
        assertThat(r.ok()).isTrue();
        assertThat(backupFiles()).containsExactly(STAMP + "-1.sql");
        assertThat(site.resolve(DIR + "/backup.lock")).doesNotExist();
        DatabaseRun rec = runs.read();
        assertThat(rec.op).isEqualTo(Op.EXPORT);
        assertThat(rec.state).isEqualTo(State.DONE);
        assertThat(rec.outcome).isEqualTo("OK");
        assertThat(rec.exportFiles).containsExactly(DIR + "/" + STAMP + "-1.sql");
        assertThat(rec.backupLock).isEqualTo(Long.toString(NOW));
        assertThat(rec.exportDir).isEqualTo(DIR);
    }

    @Test
    void exportIsRefusedWhileTheLockIsHeldAndNothingIsWritten() throws Exception {
        try (DatabaseRuns.Session held = runs.open().orElseThrow()) {
            Reply r = db.export(List.of("mac_link"), "0", lang, "1");
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).isEqualTo(DatabaseToolService.LOCKED);
        }
        assertThat(backupFiles()).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void exportIsRefusedWhileARecordIsPending() throws Exception {
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            s.begin(Op.SQL, "1", 1);
        }
        // 持锁者已不在：下一次取锁时判为 INTERRUPTED（待处理），导出按 lock_check 拒绝
        Reply r = db.export(List.of("mac_link"), "0", lang, "1");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo(lang.get("admin/database/lock_check"));
        assertThat(runs.read().state).isEqualTo(State.INTERRUPTED);
        assertThat(backupFiles()).isEmpty();
    }

    @Test
    void exportOutsideTheWhitelistIsRefusedBeforeWriting() throws Exception {
        Reply r = db.export(List.of("mac_other"), "0", lang, "1");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo(DatabaseToolService.TABLE_NOT_ALLOWED);
        assertThat(backupFiles()).isEmpty();
        assertThat(runs.read()).isNull();
    }

    @Test
    void exportWithAForeignBackupLockIsRefused() throws Exception {
        Files.writeString(site.resolve(DIR + "/backup.lock"), "123");
        Reply r = db.export(List.of("mac_link"), "0", lang, "1");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo(lang.get("admin/database/lock_check"));
        assertThat(site.resolve(DIR + "/backup.lock")).hasContent("123");
        assertThat(backupFiles()).isEmpty();
    }

    @Test
    void deleteRemovesPartsInsideTheLock() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Files.writeString(site.resolve(DIR + "/" + STAMP + "-" + i + ".sql"), "x");
        }
        Files.writeString(site.resolve(DIR + "/20250101-000000-1.sql"), "keep");
        Reply r = db.delete(Long.toString(NOW), lang);
        assertThat(r.ok()).isTrue();
        assertThat(backupFiles()).containsExactly("20250101-000000-1.sql");
        assertThat(runs.read()).as("删除不写运行记录").isNull();
    }

    @Test
    void deleteIsRefusedWhileTheLockIsHeld() throws Exception {
        Files.writeString(site.resolve(DIR + "/" + STAMP + "-1.sql"), "x");
        try (DatabaseRuns.Session held = runs.open().orElseThrow()) {
            Reply r = db.delete(Long.toString(NOW), lang);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).isEqualTo(DatabaseToolService.LOCKED);
        }
        assertThat(backupFiles()).containsExactly(STAMP + "-1.sql");
    }

    @Test
    void deleteRejectsNonDigitIds() throws Exception {
        Files.writeString(site.resolve(DIR + "/19700101-080000-1.sql"), "x");
        for (String id : new String[] {"", "0", "abc", "1e3", "-1", "123456789012345678901"}) {
            Reply r = db.delete(id, lang);
            assertThat(r.ok()).as(id).isFalse();
            assertThat(r.message()).as(id).isEqualTo(lang.get("admin/database/select_del_file"));
        }
        assertThat(backupFiles()).hasSize(1);
    }

    @Test
    void deleteOfABackupBoundToAPendingImportIsRefused() throws Exception {
        Files.writeString(site.resolve(DIR + "/" + STAMP + "-1.sql"), "x");
        Files.writeString(site.resolve(DIR + "/20250101-000000-1.sql"), "auto");
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            DatabaseRun rec = s.begin(Op.IMPORT, "1", 10);
            rec.state = State.FAILED;
            rec.sourceBackup = Long.toString(NOW);
            rec.autoBackup = new DatabaseRun.AutoBackup();
            rec.autoBackup.stamp = "1735660800"; // 2025-01-01 00:00:00 +08:00
            s.write(rec);
        }
        assertThat(db.delete(Long.toString(NOW), lang).message()).isEqualTo(DatabaseToolService.BACKUP_BOUND);
        assertThat(db.delete("1735660800", lang).message()).isEqualTo(DatabaseToolService.BACKUP_BOUND);
        assertThat(backupFiles()).hasSize(2);
        // 处理完（DONE）后可以删除
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            assertThat(runs.acknowledge(s, s.current().run, true, "1")).isEqualTo(DatabaseRuns.AckResult.OK);
        }
        assertThat(db.delete(Long.toString(NOW), lang).ok()).isTrue();
    }

    @Test
    void acknowledgeValidatesInputTakesTheLockAndLeavesPending() throws Exception {
        assertThat(db.acknowledge(null, null, "1", lang).message()).isEqualTo(lang.get("param_err"));
        assertThat(db.acknowledge("bad id", null, "1", lang).message()).isEqualTo(lang.get("param_err"));
        assertThat(db.acknowledge("r", "", "1", lang).message()).isEqualTo(lang.get("param_err"));
        assertThat(db.acknowledge("r", "2", "1", lang).message()).isEqualTo(lang.get("param_err"));
        assertThat(db.acknowledge("r", "1", "1", lang).message()).isEqualTo(DatabaseToolService.ACK_NOT_PENDING);
        String run;
        try (DatabaseRuns.Session s = runs.open().orElseThrow()) {
            run = s.begin(Op.SQL, "1", 1).run;
        }
        try (DatabaseRuns.Session held = runs.open().orElseThrow()) {
            assertThat(db.acknowledge(run, "1", "1", lang).message()).isEqualTo(DatabaseToolService.LOCKED);
        }
        assertThat(db.acknowledge("other", "1", "1", lang).message()).isEqualTo(DatabaseToolService.RUN_MISMATCH);
        assertThat(db.acknowledge(run, null, "1", lang).message()).isEqualTo(DatabaseToolService.ACK_CONFIRM_REQUIRED);
        assertThat(db.acknowledge(run, "0", "1", lang).message()).isEqualTo(DatabaseToolService.ACK_CONFIRM_REQUIRED);
        Reply ok = db.acknowledge(run, "1", "9", lang);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo(lang.get("save_ok"));
        assertThat(runs.read().state).isEqualTo(State.DONE);
        assertThat(runs.read().ack.adminId).isEqualTo("9");
        assertThat(db.acknowledge(run, "1", "9", lang).message()).isEqualTo(DatabaseToolService.ACK_NOT_PENDING);
    }
}
