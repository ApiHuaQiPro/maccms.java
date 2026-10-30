package com.maccms.backend.install.infrastructure;

import com.maccms.backend.common.web.SafeExceptionLog;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 安装向导对目标库的只读查询（B22，docs/architecture/31-b22-install-wizard.md 第 5.2 节）：全部是写死的 SELECT，没有任何参数——库名只出现在
 * 连接地址里（已按 {@link InstallTarget#DATABASE} 校验），查询用 {@code DATABASE()} 指代当前库；在 {@link InstallConnections} 的只读会话上执行。
 * 不执行任何 DDL：旧版第四、五步的 {@code CREATE DATABASE} 与 {@code install.sql}（install/controller/Index.php:162、220-235）改由部署方
 * 在库外完成，这里只核对结果。
 */
public class InstallDatabaseProbe {

    private static final Logger LOG = LoggerFactory.getLogger(InstallDatabaseProbe.class);
    /** MySQL ER_BAD_DB_ERROR（Unknown database）。 */
    static final int UNKNOWN_DATABASE = 1049;

    /** 旧版连接测试的语句（Index.php:144）。 */
    static final String VERSION = "SELECT VERSION()";
    // 表结构指纹（与测试侧 SchemaFingerprint 相同的列，库的默认字符集除外：旧版建库固定 utf8，新版由部署方建库，只要求表与列与 install.sql 一致）
    static final String FP_TABLES = "SELECT 'table', TABLE_NAME, TABLE_TYPE, ENGINE, ROW_FORMAT, TABLE_COLLATION, CREATE_OPTIONS, "
            + "TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
    static final String FP_COLUMNS = "SELECT 'column', CONCAT(TABLE_NAME, '.', COLUMN_NAME), ORDINAL_POSITION, COLUMN_DEFAULT, "
            + "IS_NULLABLE, COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_KEY, EXTRA, COLUMN_COMMENT "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION";
    static final String FP_INDEXES = "SELECT 'index', CONCAT(TABLE_NAME, '.', INDEX_NAME, '#', SEQ_IN_INDEX), NON_UNIQUE, "
            + "COLUMN_NAME, SUB_PART, INDEX_TYPE, COLLATION, NULLABLE, INDEX_COMMENT FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";
    static final String FP_TRIGGERS = "SELECT 'trigger', TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING, "
            + "ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() ORDER BY TRIGGER_NAME";
    static final String FP_ROUTINES = "SELECT 'routine', CONCAT(ROUTINE_TYPE, ' ', ROUTINE_NAME), ROUTINE_DEFINITION "
            + "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE() ORDER BY ROUTINE_TYPE, ROUTINE_NAME";
    static final String FP_EVENTS = "SELECT 'event', EVENT_NAME, EVENT_DEFINITION, STATUS "
            + "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE() ORDER BY EVENT_NAME";
    static final String FP_VIEWS = "SELECT 'view', TABLE_NAME, VIEW_DEFINITION "
            + "FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
    /**
     * 每张表的行数。只在结构已与 install.sql 一致（26 张表都是 MyISAM）之后使用：MyISAM 的 {@code TABLE_ROWS} 是精确行数
     * （InnoDB 才是估计值），所以不需要逐表 {@code COUNT(*)}（那要拼表名）。
     */
    static final String TABLE_ROWS = "SELECT TABLE_NAME, ENGINE, TABLE_ROWS FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
    /**
     * install.sql 里唯一的 InnoDB 表（视频搜索缓存，install.sql:901）的 {@code TABLE_ROWS} 只是估计值，改为直接看有没有行（固定的表名、
     * 最多读 1 行；02 规范"不读写旧缓存表"在 B22 的唯一例外，31 文档第 5.2 节）。
     */
    static final String VOD_SEARCH_ANY = "SELECT 1 FROM mac_vod_search LIMIT 1";
    static final String VOD_SEARCH = "mac_vod_search";

    /** 连接测试的结果。 */
    public enum ServerState {
        OK, CONNECT_FAILED, NO_DATABASE
    }

    /**
     * 目标库的状态。
     *
     * @param fingerprint 表结构指纹（键 → 定义，如 {@code column mac_vod.vod_id}）
     * @param rows        表名 → 行数；不是 MyISAM 的表记为 -1（行数不精确，调用方按"不为空"处理），mac_vod_search 为 0 或 1（有没有行）
     */
    public record TargetState(Map<String, String> fingerprint, Map<String, Long> rows) {
    }

    /** 读取目标库失败（连接中断等）；信息不含地址、账号。 */
    public static final class ProbeFailedException extends RuntimeException {
        ProbeFailedException() {
            super("读取目标库失败", null, false, false);
        }
    }

    private final InstallConnections connections;

    public InstallDatabaseProbe(InstallConnections connections) {
        this.connections = connections;
    }

    /** 旧版连接测试：不带库名连接并 {@code SELECT VERSION()}；新版再确认库已存在（不存在时不建库）。 */
    public ServerState checkServer(InstallTarget t) {
        try (Connection c = connections.openServerReadOnly(t); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(VERSION)) {
            rs.next();
        } catch (SQLException | RuntimeException e) {
            LOG.info("安装向导连接测试失败：{}", SafeExceptionLog.typeChain(e));
            return ServerState.CONNECT_FAILED;
        }
        try (Connection c = connections.openReadOnly(t)) {
            return ServerState.OK;
        } catch (SQLException e) {
            if (e.getErrorCode() == UNKNOWN_DATABASE) {
                return ServerState.NO_DATABASE;
            }
            LOG.info("安装向导连接目标库失败：{}", SafeExceptionLog.typeChain(e));
            return ServerState.CONNECT_FAILED;
        } catch (RuntimeException e) {
            LOG.info("安装向导连接目标库失败：{}", SafeExceptionLog.typeChain(e));
            return ServerState.CONNECT_FAILED;
        }
    }

    /** 读出结构指纹与各表行数。 */
    public TargetState inspect(InstallTarget t) {
        try (Connection c = connections.openReadOnly(t); Statement st = c.createStatement()) {
            Map<String, String> fp = new LinkedHashMap<>();
            fingerprint(st.executeQuery(FP_TABLES), fp);
            fingerprint(st.executeQuery(FP_COLUMNS), fp);
            fingerprint(st.executeQuery(FP_INDEXES), fp);
            fingerprint(st.executeQuery(FP_TRIGGERS), fp);
            fingerprint(st.executeQuery(FP_ROUTINES), fp);
            fingerprint(st.executeQuery(FP_EVENTS), fp);
            fingerprint(st.executeQuery(FP_VIEWS), fp);
            Map<String, Long> rows = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery(TABLE_ROWS)) {
                while (rs.next()) {
                    long n = rs.getLong(3);
                    boolean exact = "MyISAM".equalsIgnoreCase(rs.getString(2)) && !rs.wasNull();
                    rows.put(rs.getString(1), exact ? n : -1L);
                }
            }
            if (Long.valueOf(-1L).equals(rows.get(VOD_SEARCH))) {
                try (ResultSet rs = st.executeQuery(VOD_SEARCH_ANY)) {
                    rows.put(VOD_SEARCH, rs.next() ? 1L : 0L);
                }
            }
            return new TargetState(fp, rows);
        } catch (SQLException | RuntimeException e) {
            LOG.info("安装向导读取目标库失败：{}", SafeExceptionLog.typeChain(e));
            throw new ProbeFailedException();
        }
    }

    /** 每行：第 1、2 列拼成键，其余列拼成值（同测试侧 {@code SchemaFingerprint}）。 */
    private static void fingerprint(ResultSet rs, Map<String, String> out) throws SQLException {
        try (rs) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                String key = rs.getString(1) + " " + rs.getString(2);
                StringBuilder value = new StringBuilder();
                for (int i = 3; i <= md.getColumnCount(); i++) {
                    value.append(i > 3 ? " | " : "").append(md.getColumnLabel(i)).append('=').append(rs.getString(i));
                }
                if (out.put(key, value.toString()) != null) {
                    throw new SQLException("指纹键重复");
                }
            }
        }
    }
}
