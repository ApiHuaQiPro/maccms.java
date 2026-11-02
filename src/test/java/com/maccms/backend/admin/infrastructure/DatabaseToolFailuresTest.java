package com.maccms.backend.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.infrastructure.DatabaseToolFailures.Failure;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;

/** DBT-5 ③ 失败分类（C-74）。 */
class DatabaseToolFailuresTest {

    @Test
    void failureClassificationFollowsDbt5() {
        SQLException syntax = new SQLException("x", "42000", 1064);
        SQLException duplicate = new SQLException("x", "23000", 1062);
        SQLException link = new SQLException("x", "08S01", 0);
        SQLException client = new SQLException("x", "HY000", 2013);
        SQLException killed = new SQLException("x", "70100", 1317);
        assertThat(DatabaseToolFailures.classify(syntax, false)).isEqualTo(Failure.NOT_APPLIED);
        assertThat(DatabaseToolFailures.classify(new RuntimeException(syntax), false)).isEqualTo(Failure.NOT_APPLIED);
        assertThat(DatabaseToolFailures.classify(duplicate, true)).isEqualTo(Failure.NOT_APPLIED);
        assertThat(DatabaseToolFailures.classify(duplicate, false)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.classify(link, true)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.classify(client, true)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.classify(killed, true)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.classify(new SQLTimeoutException("x", "42000", 3024), true)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.classify(new IllegalStateException(), true)).isEqualTo(Failure.UNCERTAIN);
        assertThat(DatabaseToolFailures.sqlState(new RuntimeException(link))).isEqualTo("08S01");
        assertThat(DatabaseToolFailures.sqlState(new IllegalStateException())).isNull();
    }
}
