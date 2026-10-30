package com.maccms.backend.writedrill;

import com.maccms.backend.common.write.WriteOperation;
import com.maccms.backend.common.write.WriteStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 只用 DML 把数据恢复到快照（B07 回滚演练，docs/architecture/12-write-infrastructure.md 第 6 节）。
 *
 * <p>{@link #plan} 比较目标快照与当前快照，按表生成参数化语句：先删除快照之后新增的行（{@code DELETE ... WHERE 主键}），再把变更的行
 * 改回原值（{@code UPDATE ... SET 变更列 WHERE 主键}），最后补回被删除的行（{@code INSERT}，带原主键）。每条语句都经
 * {@link WriteStatement} 校验，并且必须恰好匹配 1 行。不使用、也不需要任何结构语句或表维护语句。
 *
 * <p>{@link #apply} 只在当前库名形如 {@code maccms_it_<14 位时间>_<8 位十六进制>} 的一次性库上执行；其他库一律拒绝。
 * 已知边界：AUTO_INCREMENT 计数器不会回退（回退需要结构语句），之后新插入的行会用更大的 id——不影响行数据与对账。
 */
public final class DmlRestore {

    static final Pattern DISPOSABLE = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");

    /** 一条恢复语句。{@code describe()} 只含语句模板与主键，不含其他列值。 */
    public record Statement(WriteOperation operation, String table, String sql, List<Object> params, List<Object> key) {

        public String describe() {
            return operation + " " + table + " " + key;
        }
    }

    private DmlRestore() {
    }

    /** 把 {@code current} 恢复成 {@code target} 所需的语句。两份快照必须是同一组表、同样的列与主键。 */
    public static List<Statement> plan(DatabaseSnapshot target, DatabaseSnapshot current) {
        SnapshotDiff diff = SnapshotDiff.between(target, current);
        List<Statement> deletes = new ArrayList<>();
        List<Statement> updates = new ArrayList<>();
        List<Statement> inserts = new ArrayList<>();
        for (SnapshotDiff.TableDiff d : diff.tables()) {
            TableSnapshot want = target.table(d.table());
            String where = " WHERE " + conditions(want.keyColumns());
            for (List<Object> key : d.added()) {
                deletes.add(statement(WriteOperation.DELETE, d.table(),
                        "DELETE FROM `" + d.table() + "`" + where, new ArrayList<>(key), key));
            }
            for (Map.Entry<List<Object>, List<String>> e : d.changed().entrySet()) {
                List<Object> row = want.rows().get(e.getKey());
                List<Object> params = new ArrayList<>();
                StringBuilder set = new StringBuilder();
                for (String col : e.getValue()) {
                    set.append(set.length() == 0 ? "" : ", ").append(TableSnapshot.quoted(List.of(col))).append(" = ?");
                    params.add(row.get(want.columns().indexOf(col)));
                }
                params.addAll(e.getKey());
                updates.add(statement(WriteOperation.UPDATE, d.table(),
                        "UPDATE `" + d.table() + "` SET " + set + where, params, e.getKey()));
            }
            for (List<Object> key : d.removed()) {
                List<Object> row = want.rows().get(key);
                String marks = String.join(", ", Collections.nCopies(want.columns().size(), "?"));
                inserts.add(statement(WriteOperation.INSERT, d.table(), "INSERT INTO `" + d.table() + "` ("
                        + TableSnapshot.quoted(want.columns()) + ") VALUES (" + marks + ")", new ArrayList<>(row), key));
            }
        }
        List<Statement> out = new ArrayList<>(deletes);
        out.addAll(updates);
        out.addAll(inserts);
        return out;
    }

    /** 在一次性库上执行恢复语句，返回执行条数。任何一条没有恰好匹配 1 行即停止并抛出。 */
    public static int apply(Connection c, List<Statement> statements) throws SQLException {
        requireDisposable(c);
        int n = 0;
        for (Statement s : statements) {
            try (PreparedStatement ps = c.prepareStatement(s.sql())) {
                for (int i = 0; i < s.params().size(); i++) {
                    ps.setObject(i + 1, s.params().get(i));
                }
                int matched = ps.executeUpdate();
                if (matched != 1) {
                    throw new IllegalStateException("恢复语句匹配 " + matched + " 行（预期 1 行）：" + s.describe());
                }
            }
            n++;
        }
        return n;
    }

    /** 当前连接的库必须是一次性库 {@code maccms_it_*}。 */
    static void requireDisposable(Connection c) throws SQLException {
        String db;
        try (PreparedStatement ps = c.prepareStatement("SELECT DATABASE()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            db = rs.getString(1);
        }
        if (db == null || !DISPOSABLE.matcher(db).matches()) {
            throw new IllegalStateException("DML 恢复只允许在一次性库 maccms_it_* 上执行");
        }
    }

    private static Statement statement(WriteOperation op, String table, String sql, List<Object> params, List<Object> key) {
        WriteStatement parsed = WriteStatement.parse(sql);
        if (parsed.operation() != op || !parsed.table().equals(table)) {
            throw new IllegalStateException("生成的恢复语句与预期不符：" + op + " " + table);
        }
        return new Statement(op, table, sql, Collections.unmodifiableList(params), key);
    }

    private static String conditions(List<String> keyColumns) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyColumns) {
            sb.append(sb.length() == 0 ? "" : " AND ").append(TableSnapshot.quoted(List.of(k))).append(" = ?");
        }
        return sb.toString();
    }
}
