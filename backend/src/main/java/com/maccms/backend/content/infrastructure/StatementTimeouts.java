package com.maccms.backend.content.infrastructure;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 识别 MySQL 服务端执行时限超时（VodMapper.xml 中 {@code MAX_EXECUTION_TIME} 提示触发）。MySQL 5.7 按语句被中止时所处的
 * 阶段报两种错误码：一般为 3024 ER_QUERY_TIMEOUT；中止发生在 filesort（排序）阶段时为 1028 ER_FILSORT_ABORT
 * （MACC-19 性能报告第 6.3 节实测）。Spring 对 MySQL 的错误码表两者都没有收录，翻译结果不是
 * {@code TransientDataAccessException}，所以由 content.application 显式归为 503。只看错误码，不读 message。
 *
 * <p>1028 只表示排序被中断，不区分中断原因：除执行时限外，{@code KILL QUERY} 打断排序时同样是 1028，也会归为 503。
 */
public final class StatementTimeouts {

    /** MySQL 5.7.8+ ER_QUERY_TIMEOUT："maximum statement execution time exceeded"。 */
    public static final int ER_QUERY_TIMEOUT = 3024;
    /** MySQL ER_FILSORT_ABORT："Sort aborted: ..."，执行时限在排序阶段触发时的错误码。 */
    public static final int ER_FILSORT_ABORT = 1028;

    private StatementTimeouts() {
    }

    public static boolean isServerTimeout(Throwable e) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = e; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (s.getErrorCode() == ER_QUERY_TIMEOUT || s.getErrorCode() == ER_FILSORT_ABORT) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
