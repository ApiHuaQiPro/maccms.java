package com.maccms.backend.writedrill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两份快照的逐行差异（B07 回滚演练）：按主键比对，分为新增行、删除行、变更行（列出变更的列名）。
 * {@link #summary()} 只输出表名、行数与主键，不输出列值（真实数据里可能有会员信息）。
 */
public final class SnapshotDiff {

    /** 一张表的差异。{@code changed}：主键 → 变更的列名。 */
    public record TableDiff(String table, List<List<Object>> added, List<List<Object>> removed,
                            Map<List<Object>, List<String>> changed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }

    private final List<TableDiff> tables;

    private SnapshotDiff(List<TableDiff> tables) {
        this.tables = List.copyOf(tables);
    }

    /** {@code before} → {@code after} 的差异。两份快照必须是同一组表、同样的列与主键。 */
    public static SnapshotDiff between(DatabaseSnapshot before, DatabaseSnapshot after) {
        if (!before.tables().keySet().equals(after.tables().keySet())) {
            throw new IllegalArgumentException("两份快照的表不同");
        }
        List<TableDiff> out = new ArrayList<>();
        for (String name : before.tables().keySet()) {
            out.add(between(before.table(name), after.table(name)));
        }
        return new SnapshotDiff(out);
    }

    static TableDiff between(TableSnapshot before, TableSnapshot after) {
        if (!before.columns().equals(after.columns()) || !before.keyColumns().equals(after.keyColumns())) {
            throw new IllegalStateException("表结构在两份快照之间变了：" + before.table());
        }
        List<List<Object>> added = new ArrayList<>();
        List<List<Object>> removed = new ArrayList<>();
        Map<List<Object>, List<String>> changed = new LinkedHashMap<>();
        for (Map.Entry<List<Object>, List<Object>> e : before.rows().entrySet()) {
            List<Object> now = after.rows().get(e.getKey());
            if (now == null) {
                removed.add(e.getKey());
                continue;
            }
            List<String> cols = new ArrayList<>();
            for (int i = 0; i < before.columns().size(); i++) {
                if (!Objects.deepEquals(e.getValue().get(i), now.get(i))) {
                    cols.add(before.columns().get(i));
                }
            }
            if (!cols.isEmpty()) {
                changed.put(e.getKey(), List.copyOf(cols));
            }
        }
        for (List<Object> key : after.rows().keySet()) {
            if (!before.rows().containsKey(key)) {
                added.add(key);
            }
        }
        return new TableDiff(before.table(), List.copyOf(added), List.copyOf(removed), changed);
    }

    public List<TableDiff> tables() {
        return tables;
    }

    public boolean isEmpty() {
        return tables.stream().allMatch(TableDiff::isEmpty);
    }

    /** 例如 {@code mac_user +0 -0 ~2 [changed [1]=[user_points]]}；不含列值。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (TableDiff t : tables) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t.table()).append(" +").append(t.added().size()).append(" -").append(t.removed().size())
                    .append(" ~").append(t.changed().size());
            if (!t.isEmpty()) {
                sb.append(" [added ").append(t.added()).append(", removed ").append(t.removed())
                        .append(", changed ").append(t.changed()).append(']');
            }
        }
        return sb.toString();
    }
}
