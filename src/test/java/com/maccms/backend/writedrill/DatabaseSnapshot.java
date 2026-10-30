package com.maccms.backend.writedrill;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一组表的数据快照（表名 → {@link TableSnapshot}），表的顺序即恢复时的处理顺序。 */
public final class DatabaseSnapshot {

    private final Map<String, TableSnapshot> tables;

    DatabaseSnapshot(Map<String, TableSnapshot> tables) {
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public static DatabaseSnapshot take(Connection c, Collection<String> tables) throws SQLException {
        Map<String, TableSnapshot> out = new LinkedHashMap<>();
        for (String t : tables) {
            out.put(t, TableSnapshot.take(c, t));
        }
        return new DatabaseSnapshot(out);
    }

    public Map<String, TableSnapshot> tables() {
        return tables;
    }

    public TableSnapshot table(String name) {
        TableSnapshot t = tables.get(name);
        if (t == null) {
            throw new IllegalArgumentException("快照中没有表：" + name);
        }
        return t;
    }
}
