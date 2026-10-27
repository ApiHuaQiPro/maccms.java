package com.maccms.backend.collect.infrastructure;

import java.util.Map;

/**
 * 采集入库写入的执行（B19，27 文档第 4 节）：经 {@link CollectIngestWriteMapper} 执行，MySQL 严格模式下的数据错误换成
 * {@link CollectDataException}（同 {@link CollectAdminWrites}）。
 */
public final class CollectIngestWrites {

    private final CollectIngestWriteMapper mapper;

    public CollectIngestWrites(CollectIngestWriteMapper mapper) {
        this.mapper = mapper;
    }

    /** 新增视频，返回自增主键。 */
    public long insertVod(Map<String, String> columns) {
        CollectInsert row = new CollectInsert(columns);
        CollectAdminWrites.run(() -> mapper.insertVod(row));
        return row.getId() == null ? 0 : row.getId();
    }

    /** 按主键修改视频（只写给出的列）；没有列时不执行。 */
    public int updateVod(Map<String, String> columns, long id) {
        return columns.isEmpty() ? 0 : CollectAdminWrites.run(() -> mapper.updateVod(columns, id));
    }
}
