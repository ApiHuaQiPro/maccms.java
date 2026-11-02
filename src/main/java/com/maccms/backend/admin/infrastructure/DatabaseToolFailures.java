package com.maccms.backend.admin.infrastructure;

import com.maccms.backend.common.write.WriteSequence;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 数据库工具语句失败的分类（技术设计 v13 DBT-5 ③，按 12 文档第 4.3 节第 3、4 条；契约 API-013/014/017）。JDBC 类型只出现在 infrastructure
 * （ModuleBoundaryTest），所以放在这里供 {@code admin.application} 的 DBT-1～DBT-3 调用。
 */
public final class DatabaseToolFailures {

    public enum Failure {
        /** 确定未生效：数据未动。 */
        NOT_APPLIED,
        /** 结果不确定：进入待处理。 */
        UNCERTAIN
    }

    private static final Set<Integer> INTERRUPTED_CODES = Set.of(1028, 1053, 1317, 1927, 3024);

    private DatabaseToolFailures() {
    }

    /**
     * SQLState {@code 42xxx}（数据库在执行前拒绝：语法、对象不存在、无权限）→ 确定未生效；单行语句的非连接、非中止类服务端错误 → 确定未生效
     * （与 {@link WriteSequence#definitelyNotApplied} 一致）；其余（{@code 08xxx}、客户端错误码 2000～2999、1028/1053/1317/1927/3024、超时、
     * 非 SQL 异常、多行语句出错）→ 结果不确定。
     */
    public static Failure classify(Throwable e, boolean singleRow) {
        SQLException s = sqlException(e);
        if (s != null && !(s instanceof SQLTimeoutException)) {
            String state = s.getSQLState();
            int code = s.getErrorCode();
            boolean connection = (state != null && state.startsWith("08")) || (code >= 2000 && code < 3000)
                    || INTERRUPTED_CODES.contains(code);
            if (!connection && state != null && state.startsWith("42")) {
                return Failure.NOT_APPLIED;
            }
        }
        return singleRow && WriteSequence.definitelyNotApplied(e) ? Failure.NOT_APPLIED : Failure.UNCERTAIN;
    }

    /** 异常链上第一个 SQLException 的 SQLState（记日志与提示用；没有为 null）。 */
    public static String sqlState(Throwable e) {
        SQLException s = sqlException(e);
        return s == null ? null : s.getSQLState();
    }

    private static SQLException sqlException(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException s) {
                return s;
            }
        }
        return null;
    }
}
