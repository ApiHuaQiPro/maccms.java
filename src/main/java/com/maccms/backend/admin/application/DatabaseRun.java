package com.maccms.backend.admin.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库工具的运行记录 {@code dbtool-run.json}（技术设计 v13 C-73、DBT-5 ③⑥⑨⑩ 与状态机）。只在持有 {@code dbtool.lock} 时读后写（原子替换），
 * 页面显示可以不持锁只读。字段名按 JSON 的 snake_case 落盘；只记标识与摘要，<b>不记 SQL 原文、数据值、连接串</b>。
 */
public final class DatabaseRun {

    public enum Op { OPTIMIZE, REPAIR, SQL, IMPORT, EXPORT }

    public enum Phase { AUTO_BACKUP, RESTORE }

    public enum State {
        RUNNING, DONE, FAILED, UNKNOWN, INTERRUPTED, RECOVERING, RECOVERED, RECOVERY_FAILED, RECOVERY_UNKNOWN, RECOVERY_INTERRUPTED
    }

    /** “待处理”：{@code acknowledge} 是离开它们的唯一出口。 */
    public static final Set<State> PENDING = Set.of(State.FAILED, State.UNKNOWN, State.INTERRUPTED, State.RECOVERY_FAILED,
            State.RECOVERY_UNKNOWN, State.RECOVERY_INTERRUPTED, State.RECOVERED);

    public String run;
    public Op op;
    public Phase phase;
    public State state;
    public String adminId;
    /** host、pid、进程启动时间，只作审计显示。 */
    public String owner;
    public String startedAt;
    public String updatedAt;
    public int total;
    public int executed;
    public Integer failedIndex;
    public String outcome;
    public String sqlstate;
    public Integer errorCode;
    public int tablesDone;
    public String tableCurrent;
    public int insertedCurrent;
    /** DBT-1 逐表结果（表名 → OK / FAILED:SQLState）。 */
    public Map<String, String> tableResults = new LinkedHashMap<>();
    /** DBT-3 原备份时间戳（Unix 秒的文本）。 */
    public String sourceBackup;
    public AutoBackup autoBackup;
    /** 导出或自动备份本次已创建的分卷文件名（每新建一卷先记入再创建）。 */
    public List<String> exportFiles = new ArrayList<>();
    /** 导出与自动备份所在的备份目录（站点根相对路径，{@code backup.lock} 在其中）。 */
    public String exportDir;
    /** 本次写入 {@code backup.lock} 的值。 */
    public String backupLock;
    public Interrupted interrupted;
    public Recovery recovery;
    public Ack ack;

    public static final class AutoBackup {
        public String stamp;
        public List<String> files = new ArrayList<>();
        public Map<String, String> sha256 = new LinkedHashMap<>();
        public List<String> tables = new ArrayList<>();
    }

    public static final class Interrupted {
        public String at;
        public State fromState;
        public Phase fromPhase;
        /** {@code OK} / {@code LOCK_KEPT} / {@code FAILED}。 */
        public String cleanup;
        public List<String> leftovers = new ArrayList<>();
    }

    public static final class Recovery {
        public String target;
        public int attempt;
        public String lastResult;
        public String lastAt;
    }

    public static final class Ack {
        public String adminId;
        public String at;
        public boolean acceptedAsIs;
    }

    public boolean pending() {
        return state != null && PENDING.contains(state);
    }

    /** 是否处于“执行中”（持锁者读到时说明原占用者已不在，须按中断表改写）。 */
    public boolean running() {
        return state == State.RUNNING || state == State.RECOVERING;
    }
}
