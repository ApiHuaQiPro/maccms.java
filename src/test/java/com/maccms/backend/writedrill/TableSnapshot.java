package com.maccms.backend.writedrill;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一张表的全部数据行快照（回滚演练工具，B07；docs/architecture/12-write-infrastructure.md 第 6 节）：按主键排序读出每一列的
 * {@code getObject} 值。只读，不加锁——前提是取快照时没有其他写入（演练在一次性库上进行；整体切换时在停止旧站写入之后取）。
 *
 * <p>表名、列名、主键都从 information_schema 读取并校验为 {@code [A-Za-z0-9_]+}，再加反引号拼进 SELECT；不接受调用方给的列名。
 * 没有主键的表、行数超过 {@link #MAX_ROWS} 的表拒绝（install.sql 的 26 张表都有主键）。
 */
public final class TableSnapshot {

    public static final int MAX_ROWS = 200_000;
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    static final Pattern TABLE = Pattern.compile("mac_[a-z0-9_]+");

    private final String table;
    private final List<String> columns;
    private final List<String> keyColumns;
    private final Map<List<Object>, List<Object>> rows;

    TableSnapshot(String table, List<String> columns, List<String> keyColumns, Map<List<Object>, List<Object>> rows) {
        this.table = table;
        this.columns = List.copyOf(columns);
        this.keyColumns = List.copyOf(keyColumns);
        this.rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
    }

    public static TableSnapshot take(Connection c, String table) throws SQLException {
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("只能对 mac_* 表取快照");
        }
        List<String> columns = strings(c, "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", table);
        List<String> keys = strings(c, "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = 'PRIMARY' ORDER BY SEQ_IN_INDEX", table);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("表不存在：" + table);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("表没有主键，不能逐行比对：" + table);
        }
        for (String col : columns) {
            if (!IDENTIFIER.matcher(col).matches()) {
                throw new IllegalArgumentException("列名不合规：" + table);
            }
        }
        List<Integer> keyIndexes = new ArrayList<>();
        for (String k : keys) {
            keyIndexes.add(columns.indexOf(k));
        }
        String sql = "SELECT " + quoted(columns) + " FROM `" + table + "` ORDER BY " + quoted(keys);
        Map<List<Object>, List<Object>> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new IllegalStateException("表行数超过快照上限 " + MAX_ROWS + "：" + table);
                }
                List<Object> values = new ArrayList<>(columns.size());
                for (int i = 1; i <= columns.size(); i++) {
                    values.add(rs.getObject(i));
                }
                List<Object> key = new ArrayList<>(keys.size());
                for (int i : keyIndexes) {
                    key.add(values.get(i));
                }
                rows.put(List.copyOf(key), Collections.unmodifiableList(values));
            }
        }
        return new TableSnapshot(table, columns, keys, rows);
    }

    public String table() {
        return table;
    }

    public List<String> columns() {
        return columns;
    }

    public List<String> keyColumns() {
        return keyColumns;
    }

    /** 主键值 → 整行（列顺序同 {@link #columns()}）。值可能是 null（可空列），所以列表不是 {@code List.copyOf}。 */
    public Map<List<Object>, List<Object>> rows() {
        return rows;
    }

    static String quoted(List<String> identifiers) {
        StringBuilder sb = new StringBuilder();
        for (String id : identifiers) {
            if (!IDENTIFIER.matcher(id).matches()) {
                throw new IllegalArgumentException("标识符不合规");
            }
            sb.append(sb.length() == 0 ? "" : ", ").append('`').append(id).append('`');
        }
        return sb.toString();
    }

    private static List<String> strings(Connection c, String sql, String param) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
