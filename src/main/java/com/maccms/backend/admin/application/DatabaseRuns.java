package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.DatabaseRun.Op;
import com.maccms.backend.admin.application.DatabaseRun.Phase;
import com.maccms.backend.admin.application.DatabaseRun.State;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 数据库工具的互斥、运行记录与状态机（技术设计 v13 C-73、DBT-5 ③④⑥⑩；契约 API-010～018、{@code x-interrupt-transitions}）。
 *
 * <p>所有动作的顺序统一为“取锁（{@link #open}）→ 中断判定（打开时自动完成）→ 状态检查 → 预检 → 写 {@code RUNNING}”。锁是站点根下
 * {@value #LOCK} 上的独占 OS 文件锁（{@link LegacySiteFiles#tryExclusiveLock}），拿不到立即拒绝、不等待；持锁者就是占用者，进程退出时由操作系统释放，
 * 所以持锁者读到 {@code RUNNING}/{@code RECOVERING} 即可确定原占用者已不在，按 {@link #interrupt} 的唯一转移改写后才继续。
 * 记录 {@value #RECORD} 只在持锁时读后写（原子替换）。
 */
public final class DatabaseRuns {

    public static final String DIR = "application/data/backup/database";
    public static final String LOCK = DIR + "/dbtool.lock";
    public static final String RECORD = DIR + "/dbtool-run.json";

    static final Logger LOG = LoggerFactory.getLogger("maccms.admin.dbtool");

    private static final JsonMapper JSON = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private static final int MAX_RECORD_BYTES = 1 << 20;
    /** 中断清理只删这样的分卷（导出与自动备份的文件名规则，同 B15）。 */
    private static final Pattern BACKUP_PART = Pattern.compile("^application/data/backup/[A-Za-z0-9_/.-]*/\\d{8}-\\d{6}-\\d+\\.sql(?:\\.gz)?$");
    private static final Set<Integer> INTERRUPTED_CODES = Set.of(1028, 1053, 1317, 1927, 3024);

    private final LegacySiteFiles files;
    private final Clock clock;
    private final Supplier<String> ids;

    public DatabaseRuns(LegacySiteFiles files, Clock clock, Supplier<String> ids) {
        this.files = files;
        this.clock = clock;
        this.ids = ids;
    }

    // ------------------------------------------------------------------ 锁与会话

    /** 持锁期间的会话：{@link #current()} 是中断判定之后的记录（可能为 null）；{@link #write} 原子替换落盘。 */
    public final class Session implements AutoCloseable {
        private final LegacySiteFiles.ExclusiveLock lock;
        private DatabaseRun current;

        private Session(LegacySiteFiles.ExclusiveLock lock, DatabaseRun current) {
            this.lock = lock;
            this.current = current;
        }

        public DatabaseRun current() {
            return current;
        }

        public void write(DatabaseRun r) {
            r.updatedAt = clock.instant().toString();
            files.replace(RECORD, JSON.writeValueAsBytes(r), null);
            current = r;
        }

        /** 新建 {@code RUNNING} 记录并落盘（调用方已做完状态检查与预检）。 */
        public DatabaseRun begin(Op op, String adminId, int total) {
            DatabaseRun r = new DatabaseRun();
            r.run = ids.get();
            r.op = op;
            r.state = State.RUNNING;
            r.adminId = adminId;
            r.owner = owner();
            r.startedAt = clock.instant().toString();
            r.total = total;
            write(r);
            LOG.info("dbtool begin run={} op={} admin_id={} total={}", r.run, op, adminId, total);
            return r;
        }

        /** 状态机的统一拒绝：待处理（含 {@code RECOVERED}）时任何新动作都不开始。 */
        public boolean blocksNewOperation() {
            return current != null && current.pending();
        }

        @Override
        public void close() {
            lock.close();
        }
    }

    /** 取锁并完成中断判定；拿不到锁 → 空（调用方拒绝“已有数据库操作在执行”）。 */
    public Optional<Session> open() {
        Optional<LegacySiteFiles.ExclusiveLock> lock = files.tryExclusiveLock(LOCK);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        try {
            DatabaseRun r = read();
            Session s = new Session(lock.get(), r);
            if (r != null && r.running()) {
                s.write(judge(r));
            }
            return Optional.of(s);
        } catch (RuntimeException e) {
            lock.get().close();
            throw e;
        }
    }

    /** 页面加载：不等待地取锁 → 拿不到为“执行中”；拿到则完成中断判定后立即释放（判定幂等：已不是执行中的记录不改写、不写日志）。 */
    public record Probe(boolean busy, DatabaseRun record) {
    }

    public Probe probe() {
        Optional<Session> s = open();
        if (s.isEmpty()) {
            return new Probe(true, null);
        }
        try (Session session = s.get()) {
            return new Probe(false, session.current());
        }
    }

    /** 不持锁只读（页面显示用）；文件不存在为 null。 */
    public DatabaseRun read() {
        if (files.stat(RECORD) == null) {
            return null;
        }
        return JSON.readValue(files.read(RECORD, MAX_RECORD_BYTES), DatabaseRun.class);
    }

    // ------------------------------------------------------------------ 中断判定（DBT-5 ⑩）

    /** 中断改写的结果：新记录与日志级别（清理失败时升为 ERROR）。 */
    record Transition(DatabaseRun record, Level level) {
    }

    /** 按 {@code op}、{@code phase}、{@code state} 唯一确定新状态并执行清理（只删记录点名的文件），记日志，返回新记录。 */
    private DatabaseRun judge(DatabaseRun r) {
        Transition t = interrupt(r);
        DatabaseRun n = t.record();
        log(t.level(), "dbtool interrupted run={} op={} phase={} state={} executed={} tables={} cleanup={}", n.run, n.op,
                n.interrupted.fromPhase, n.state, n.executed, n.tablesDone, n.interrupted.cleanup);
        return n;
    }

    /** {@code x-interrupt-transitions} 的六行（V-31 ⑪～⑯）。 */
    Transition interrupt(DatabaseRun r) {
        DatabaseRun.Interrupted i = new DatabaseRun.Interrupted();
        i.at = clock.instant().toString();
        i.fromState = r.state;
        i.fromPhase = r.phase;
        r.interrupted = i;
        Level level;
        if (r.state == State.RECOVERING) {
            r.state = State.RECOVERY_INTERRUPTED;
            if (r.recovery == null) {
                r.recovery = new DatabaseRun.Recovery();
            }
            r.recovery.lastResult = "INTERRUPTED";
            r.recovery.lastAt = i.at;
            level = Level.ERROR;
        } else if (r.op == Op.OPTIMIZE || r.op == Op.REPAIR) {
            r.state = State.DONE;
            r.outcome = "INTERRUPTED";
            level = Level.WARN;
        } else if (r.op == Op.SQL) {
            r.state = State.INTERRUPTED;
            level = Level.ERROR;
        } else if (r.op == Op.IMPORT && r.phase == Phase.RESTORE) {
            r.state = State.INTERRUPTED;
            level = Level.ERROR;
        } else {
            // EXPORT，或 IMPORT 的 AUTO_BACKUP 阶段（以及缺失 phase 的 IMPORT：还原语句只在 phase=RESTORE 落盘后发送）
            r.state = State.DONE;
            r.outcome = r.op == Op.IMPORT ? "INTERRUPTED_BEFORE_WRITE" : "INTERRUPTED";
            cleanup(r, i);
            level = i.cleanup.equals("OK") ? Level.WARN : Level.ERROR;
        }
        if (i.cleanup == null) {
            i.cleanup = "NONE";
        }
        return new Transition(r, level);
    }

    /** 只删 {@code export_files} 点名的分卷，以及内容等于 {@code backup_lock} 的 {@code backup.lock}；不按通配删除。 */
    private void cleanup(DatabaseRun r, DatabaseRun.Interrupted i) {
        boolean failed = false;
        boolean kept = false;
        for (String f : r.exportFiles) {
            if (!BACKUP_PART.matcher(f).matches()) {
                failed = true;
                i.leftovers.add(f);
                continue;
            }
            try {
                files.delete(f);
                if (files.stat(f) != null) {
                    failed = true;
                    i.leftovers.add(f);
                }
            } catch (RuntimeException e) {
                failed = true;
                i.leftovers.add(f);
            }
        }
        if (r.backupLock != null && r.exportDir != null) {
            String lock = r.exportDir + "/backup.lock";
            try {
                if (files.stat(lock) != null) {
                    String content = new String(files.read(lock, 64), StandardCharsets.US_ASCII);
                    if (content.equals(r.backupLock)) {
                        files.delete(lock);
                        if (files.stat(lock) != null) {
                            failed = true;
                            i.leftovers.add(lock);
                        }
                    } else {
                        kept = true;
                        i.leftovers.add(lock);
                    }
                }
            } catch (RuntimeException e) {
                failed = true;
                i.leftovers.add(lock);
            }
        }
        i.cleanup = failed ? "FAILED" : kept ? "LOCK_KEPT" : "OK";
    }

    // ------------------------------------------------------------------ 失败分类（DBT-5 ③）

    public enum Failure { NOT_APPLIED, UNCERTAIN }

    /**
     * 12 文档第 4.3 节：SQLState {@code 42xxx}（数据库在执行前拒绝）→ 确定未生效；单行语句的非连接、非中止类服务端错误 → 确定未生效
     * （与 {@link WriteSequence#definitelyNotApplied} 一致）；其余（{@code 08xxx}、客户端错误码 2000～2999、1028/1053/1317/1927/3024、超时、
     * 非 SQL 异常、多行语句出错）→ 结果不确定。
     */
    public static Failure classify(Throwable e, boolean singleRow) {
        SQLException s = sqlException(e);
        if (s != null && !(s instanceof java.sql.SQLTimeoutException)) {
            String state = s.getSQLState();
            int code = s.getErrorCode();
            boolean connection = (state != null && state.startsWith("08")) || (code >= 2000 && code < 3000)
                    || INTERRUPTED_CODES.contains(code);
            if (!connection && state != null && state.startsWith("42")) {
                return Failure.NOT_APPLIED;
            }
        }
        return singleRow && WriteSequence.definitelyNotApplied(e) ? Failure.NOT_APPLIED : Failure.UNCERTAIN;
    }

    static SQLException sqlException(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException s) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ acknowledge（API-018）

    public enum AckResult { OK, RUN_MISMATCH, CONFIRM_REQUIRED, NOT_PENDING }

    /** 在会话内处理“我已处理”：只做一次原子替换。 */
    public AckResult acknowledge(Session s, String run, boolean acceptedAsIs, String adminId) {
        DatabaseRun r = s.current();
        if (r == null) {
            return AckResult.NOT_PENDING;
        }
        if (!r.run.equals(run)) {
            return AckResult.RUN_MISMATCH;
        }
        if (!r.pending()) {
            return AckResult.NOT_PENDING;
        }
        boolean recovered = r.state == State.RECOVERED;
        if (!recovered && !acceptedAsIs) {
            return AckResult.CONFIRM_REQUIRED;
        }
        DatabaseRun.Ack ack = new DatabaseRun.Ack();
        ack.adminId = adminId;
        ack.at = clock.instant().toString();
        ack.acceptedAsIs = !recovered;
        r.ack = ack;
        State from = r.state;
        r.state = State.DONE;
        s.write(r);
        LOG.info("dbtool acknowledge run={} op={} from={} admin_id={} accepted_as_is={}", r.run, r.op, from, adminId, ack.acceptedAsIs);
        return AckResult.OK;
    }

    // ------------------------------------------------------------------ 其他

    static void log(Level level, String format, Object... args) {
        switch (level) {
            case ERROR -> LOG.error(format, args);
            case WARN -> LOG.warn(format, args);
            default -> LOG.info(format, args);
        }
    }

    private static String owner() {
        ProcessHandle self = ProcessHandle.current();
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            host = "?";
        }
        return host + "/" + self.pid() + "/" + self.info().startInstant().map(Object::toString).orElse("?");
    }
}
