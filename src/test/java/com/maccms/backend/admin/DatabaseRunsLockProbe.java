package com.maccms.backend.admin;

import com.maccms.backend.admin.application.DatabaseRun;
import com.maccms.backend.admin.application.DatabaseRuns;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code DatabaseRunsLockIT} 的子进程入口（C-74，只在 src/test）：
 * <ul>
 * <li>{@code hold <application 目录> <op> [导出分卷相对路径]}：取锁、写 {@code RUNNING} 记录后输出 {@code LOCKED}，然后一直持锁，等父进程杀掉；</li>
 * <li>{@code race <application 目录> <持锁毫秒>}：取锁一次，输出 {@code GOT} 或 {@code BUSY}；拿到则持锁指定毫秒后正常退出。</li>
 * </ul>
 */
public final class DatabaseRunsLockProbe {

    private DatabaseRunsLockProbe() {
    }

    public static void main(String[] args) throws Exception {
        LegacySiteFiles files = new LegacySiteFiles(new LegacyConfigProperties(args[1]));
        DatabaseRuns runs = new DatabaseRuns(files, Clock.systemUTC(), () -> UUID.randomUUID().toString());
        Optional<DatabaseRuns.Session> s = runs.open();
        if (args[0].equals("race")) {
            System.out.println(s.isPresent() ? "GOT" : "BUSY");
            System.out.flush();
            if (s.isPresent()) {
                Thread.sleep(Long.parseLong(args[2]));
                s.get().close();
            }
            return;
        }
        DatabaseRuns.Session session = s.orElseThrow();
        DatabaseRun r = session.begin(DatabaseRun.Op.valueOf(args[2]), "1", 2);
        if (args.length > 3) {
            r.exportDir = DatabaseRuns.DIR;
            r.exportFiles.add(args[3]);
            r.backupLock = "42";
            session.write(r);
            files.replace(DatabaseRuns.DIR + "/backup.lock", "42".getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
            files.replace(args[3], "-- half".getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
        }
        System.out.println("LOCKED");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
