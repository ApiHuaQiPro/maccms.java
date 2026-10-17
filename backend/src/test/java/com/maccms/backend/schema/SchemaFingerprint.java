package com.maccms.backend.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 一次性库的表结构指纹（B02 表结构护栏的运行期部分，docs/architecture/11-guards-and-legacy-config.md 第 3.2 节）：
 * 从 information_schema 读取库的默认字符集、每张表的类型/引擎/行格式/排序规则/选项、每列的定义、每个索引的定义，
 * 以及触发器、存储过程/函数、事件与视图。不含随数据变化的项（行数、AUTO_INCREMENT、更新时间、数据长度）。
 *
 * <p>用法：导入 install.sql 后取一次，测试结束（删库前）再取一次，两者必须完全相同。只在 {@code -Pmysql-it} 的一次性库
 * {@code maccms_it_*} 上使用。
 */
public final class SchemaFingerprint {

    private SchemaFingerprint() {
    }

    /** 读取 {@code db} 的结构指纹：键为对象（如 {@code column mac_vod.vod_id}），值为定义。 */
    public static Map<String, String> of(Connection c, String db) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        rows(c, "SELECT 'schema', SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?", db, 2, out);
        rows(c, "SELECT 'table', TABLE_NAME, TABLE_TYPE, ENGINE, ROW_FORMAT, TABLE_COLLATION, CREATE_OPTIONS, TABLE_COMMENT "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME", db, 2, out);
        rows(c, "SELECT 'column', CONCAT(TABLE_NAME, '.', COLUMN_NAME), ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, "
                + "COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_KEY, EXTRA, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION", db, 2, out);
        rows(c, "SELECT 'index', CONCAT(TABLE_NAME, '.', INDEX_NAME, '#', SEQ_IN_INDEX), NON_UNIQUE, COLUMN_NAME, "
                + "SUB_PART, INDEX_TYPE, COLLATION, NULLABLE, INDEX_COMMENT "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? "
                + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", db, 2, out);
        rows(c, "SELECT 'trigger', TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING, ACTION_STATEMENT "
                + "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME", db, 2, out);
        rows(c, "SELECT 'routine', CONCAT(ROUTINE_TYPE, ' ', ROUTINE_NAME), ROUTINE_DEFINITION "
                + "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_TYPE, ROUTINE_NAME", db, 2, out);
        rows(c, "SELECT 'event', EVENT_NAME, EVENT_DEFINITION, STATUS "
                + "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ? ORDER BY EVENT_NAME", db, 2, out);
        rows(c, "SELECT 'view', TABLE_NAME, VIEW_DEFINITION "
                + "FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME", db, 2, out);
        return out;
    }

    /** 两个指纹的差异（空列表表示相同），每行一个对象，便于定位。 */
    public static List<String> diff(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            String a = before.get(k);
            String b = after.get(k);
            if (a == null) {
                out.add("新增 " + k + " = " + b);
            } else if (b == null) {
                out.add("删除 " + k + "（原为 " + a + "）");
            } else if (!Objects.equals(a, b)) {
                out.add("变更 " + k + "：" + a + " → " + b);
            }
        }
        return out;
    }

    /** 统计某类对象的个数（如 {@code table}）。 */
    public static long count(Map<String, String> fingerprint, String kind) {
        return fingerprint.keySet().stream().filter(k -> k.startsWith(kind + " ")).count();
    }

    /** 每行：第 1 列为对象类型，第 {@code keyCols} 列之前拼成键，其余列拼成值。 */
    private static void rows(Connection c, String sql, String db, int keyCols, Map<String, String> out)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    StringBuilder key = new StringBuilder();
                    for (int i = 1; i <= keyCols; i++) {
                        key.append(i > 1 ? " " : "").append(rs.getString(i));
                    }
                    StringBuilder value = new StringBuilder();
                    for (int i = keyCols + 1; i <= md.getColumnCount(); i++) {
                        value.append(i > keyCols + 1 ? " | " : "").append(md.getColumnLabel(i)).append('=')
                                .append(rs.getString(i));
                    }
                    if (out.put(key.toString(), value.toString()) != null) {
                        throw new IllegalStateException("指纹键重复：" + key);
                    }
                }
            }
        }
    }
}
