package com.maccms.backend.collect.infrastructure;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 采集配置管理写入的执行（B17，25 文档第 6 节）：经 {@link CollectAdminWriteMapper} 执行，把 MySQL 严格模式下的数据错误换成
 * {@link CollectDataException}，其余异常原样抛出（与 B13、B14 的后台写入相同）。返回匹配行数。
 */
public final class CollectAdminWrites {

    /** 数据错误：1048 列不能为 NULL、1264 超出范围、1265 截断、1292 值不正确、1364 没有默认值的列未给值、1366 整数/字符串值不正确、1406 过长。 */
    private static final Set<Integer> DATA_ERRORS = Set.of(1048, 1264, 1265, 1292, 1364, 1366, 1406);

    private final CollectAdminWriteMapper mapper;

    public CollectAdminWrites(CollectAdminWriteMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- mac_collect

    /** 新增采集源，返回自增主键。 */
    public long insertCollect(Map<String, String> columns) {
        CollectInsert row = new CollectInsert(columns);
        run(() -> mapper.insertCollect(row));
        return row.getId() == null ? 0 : row.getId();
    }

    /** 按主键整行覆盖（只写给出的列）；没有列时不执行。 */
    public int updateCollect(Map<String, String> columns, long id) {
        return columns.isEmpty() ? 0 : run(() -> mapper.updateCollect(columns, id));
    }

    public int deleteCollects(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteCollects(ids));
    }

    // ---------------------------------------------------------------- mac_cj_node 与其下的内容、采集历史

    public long insertCjNode(Map<String, String> columns) {
        CollectInsert row = new CollectInsert(columns);
        run(() -> mapper.insertCjNode(row));
        return row.getId() == null ? 0 : row.getId();
    }

    public int updateCjNode(Map<String, String> columns, long id) {
        return columns.isEmpty() ? 0 : run(() -> mapper.updateCjNode(columns, id));
    }

    public int deleteCjNodes(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteCjNodes(ids));
    }

    public int deleteCjHistories(List<String> md5s) {
        return md5s.isEmpty() ? 0 : run(() -> mapper.deleteCjHistories(md5s));
    }

    public int deleteCjContentsOfNodes(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteCjContentsOfNodes(ids));
    }

    private static <T> T run(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (dataError(e)) {
                throw new CollectDataException();
            }
            throw e;
        }
    }

    private static boolean dataError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && DATA_ERRORS.contains(s.getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}
