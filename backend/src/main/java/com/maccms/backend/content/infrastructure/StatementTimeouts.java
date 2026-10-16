package com.maccms.backend.content.infrastructure;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 识别 MySQL 服务端执行时限超时（VodMapper.xml 中 {@code MAX_EXECUTION_TIME} 提示触发的错误 3024
 * ER_QUERY_TIMEOUT）。Spring 对 MySQL 的错误码表没有收录 3024，翻译结果不是
 * {@code TransientDataAccessException}，所以由 content.application 显式归为 503。只看错误码，不读 message。
 */
public final class StatementTimeouts {

    /** MySQL 5.7.8+ ER_QUERY_TIMEOUT："maximum statement execution time exceeded"。 */
    public static final int ER_QUERY_TIMEOUT = 3024;

    private StatementTimeouts() {
    }

    public static boolean isServerTimeout(Throwable e) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = e; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (s.getErrorCode() == ER_QUERY_TIMEOUT) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
