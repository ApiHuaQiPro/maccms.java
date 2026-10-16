package com.maccms.backend.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * 服务端执行时限的识别：3024（ER_QUERY_TIMEOUT）与 1028（ER_FILSORT_ABORT，时限在排序阶段触发，MACC-19 报告第 6.3 节）
 * 沿 cause 链与 SQLException 的 nextException 链按错误码识别；其他错误码不算超时，分类保持不变。
 */
class StatementTimeoutsTest {

    private static UncategorizedSQLException translated(SQLException cause) {
        return new UncategorizedSQLException("task", "SELECT 1", cause);
    }

    @Test
    void bothTimeoutCodesAreRecognisedThroughTheCauseChain() {
        for (int code : new int[] {StatementTimeouts.ER_QUERY_TIMEOUT, StatementTimeouts.ER_FILSORT_ABORT}) {
            SQLException sql = new SQLException("synthetic", "HY000", code);
            assertThat(StatementTimeouts.isServerTimeout(sql)).as("%d", code).isTrue();
            assertThat(StatementTimeouts.isServerTimeout(translated(sql))).as("%d", code).isTrue();
            assertThat(StatementTimeouts.isServerTimeout(
                    new IllegalStateException("wrapper", translated(sql)))).as("%d", code).isTrue();
        }
    }

    @Test
    void timeoutCodeInNextExceptionIsRecognised() {
        SQLException head = new SQLException("synthetic", "HY000", 1105);
        head.setNextException(new SQLException("synthetic", "HY000", StatementTimeouts.ER_FILSORT_ABORT));
        assertThat(StatementTimeouts.isServerTimeout(translated(head))).isTrue();
    }

    @Test
    void otherErrorCodesAreNotTimeouts() {
        // 1064 语法错误、1205 锁等待超时（Spring 另有 503 分支）、1317 查询被中断、1105 未知错误、0 无错误码
        for (int code : new int[] {1064, 1205, 1317, 1105, 0}) {
            SQLException sql = new SQLException("synthetic", "HY000", code);
            assertThat(StatementTimeouts.isServerTimeout(translated(sql))).as("%d", code).isFalse();
        }
        assertThat(StatementTimeouts.isServerTimeout(
                new BadSqlGrammarException("task", "SELECT 1", new SQLException("synthetic", "42000", 1064)))).isFalse();
        assertThat(StatementTimeouts.isServerTimeout(new IllegalStateException("no sql cause"))).isFalse();
        assertThat(StatementTimeouts.isServerTimeout(null)).isFalse();
    }

    @Test
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(StatementTimeouts.isServerTimeout(a)).isFalse();
    }
}
