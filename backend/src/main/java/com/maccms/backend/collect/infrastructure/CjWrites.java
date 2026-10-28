package com.maccms.backend.collect.infrastructure;

import java.sql.SQLException;
import java.util.List;

/**
 * 自定义规则采集写入的执行（B20，28 文档第 5 节）：经 {@link CjWriteMapper} 执行，MySQL 严格模式下的数据错误换成 {@link CollectDataException}
 * （同 {@link CollectAdminWrites}）；采集历史的主键冲突（并发记下同一网址）当作"已有"。返回匹配行数。
 */
public final class CjWrites {

    private static final int DUPLICATE_KEY = 1062;

    private final CjWriteMapper mapper;

    public CjWrites(CjWriteMapper mapper) {
        this.mapper = mapper;
    }

    /** 记下采集历史；已有时 0。 */
    public int insertHistory(String md5) {
        try {
            return CollectAdminWrites.run(() -> mapper.insertHistory(md5));
        } catch (RuntimeException e) {
            if (sqlError(e, DUPLICATE_KEY)) {
                return 0;
            }
            throw e;
        }
    }

    public int insertContent(long nodeid, String url, String title) {
        return CollectAdminWrites.run(() -> mapper.insertContent(nodeid, url, title));
    }

    public int updateCollected(long id, long nodeid, String data) {
        return CollectAdminWrites.run(() -> mapper.updateCollected(id, nodeid, data));
    }

    public int updatePublished(List<Long> ids) {
        return ids.isEmpty() ? 0 : CollectAdminWrites.run(() -> mapper.updatePublished(ids));
    }

    public int deleteContents(List<Long> ids) {
        return ids.isEmpty() ? 0 : CollectAdminWrites.run(() -> mapper.deleteContents(ids));
    }

    public int deleteAllContents() {
        return CollectAdminWrites.run(mapper::deleteAllContents);
    }

    private static boolean sqlError(Throwable e, int code) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getErrorCode() == code) {
                return true;
            }
        }
        return false;
    }
}
