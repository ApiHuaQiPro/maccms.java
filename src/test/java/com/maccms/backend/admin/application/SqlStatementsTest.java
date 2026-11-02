package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.SqlStatements.Prepared;
import com.maccms.backend.admin.application.SqlStatements.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** V-23 ①～④：DBT-2 整段语义（技术设计 §4.13、§6.13 DBT-2；契约 API-013）。 */
class SqlStatementsTest {

    private static Prepared prep(String sql) {
        return SqlStatements.prepare(sql, "mac_");
    }

    @Test
    void emptyAfterPhpTrimOrPhpEmptyIsNotExecuted() {
        assertThat(prep(null).verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(prep("").verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(prep(" \t\r\n\0\u000B").verdict()).isEqualTo(Verdict.EMPTY);
        // PHP empty("0") 为真：不执行，同样返回 run_ok
        assertThat(prep("  0 ").verdict()).isEqualTo(Verdict.EMPTY);
    }

    @Test
    void prefixPlaceholderIsReplacedAndTextOtherwiseUnchanged() {
        Prepared p = prep("  UPDATE {pre}vod SET vod_hits = vod_hits + 1 WHERE vod_id = 1;\n");
        assertThat(p.verdict()).isEqualTo(Verdict.EXECUTE);
        assertThat(p.sql()).isEqualTo("UPDATE mac_vod SET vod_hits = vod_hits + 1 WHERE vod_id = 1;");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "select 1",
        "SELECT * FROM mac_vod",
        "/* x */ SeLeCt 1",
        "-- c\nselect 1",
        "  \n select 1; insert into mac_vod (vod_id) values (1)",
        "insert into mac_vod select * from mac_vod into outfile '/tmp/x'",
        "update mac_vod set vod_name = load_file('/etc/passwd')",
        "insert into mac_vod (vod_name) values ('dumpfile')",
        "update mac_vod set vod_name = 'OUTFILE'",
        "update mac_vod set vod_name = 'x' where 1 /* into/**/outfile */",
    })
    void phpSkipRuleIsCopiedVerbatim(String sql) {
        assertThat(prep(sql).verdict()).isEqualTo(Verdict.SKIPPED);
    }

    @Test
    void phpSkipNormalisationOnlyLowersAscii() {
        // 'İ'（U+0130）在 Java 的 toLowerCase 下会变成两个字符；PHP strtolower 只改 A-Z
        assertThat(SqlStatements.phpSkipNormal("SELECTİ /*a*/ X\t--c\n Y")).isEqualTo("selectİ x y");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "update mac_vod set vod_hits = 1; delete from mac_vod",
        "delete from mac_vod where vod_id = 1;;",
        "insert into mac_vod (vod_name) values ('a');\n/* c */ update mac_vod set vod_hits=1",
        "update mac_vod set vod_name = 'a\\'; drop table x' ; drop table mac_vod",
    })
    void multipleStatementsAreRejectedBeforeSending(String sql) {
        assertThat(prep(sql).verdict()).isEqualTo(Verdict.MULTI_STATEMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "update mac_vod set vod_name = 'a;b' where vod_id = 1",
        "update mac_vod set vod_name = \"a;b\" where vod_id = 1;",
        "update `mac;vod` set vod_name = 1",
        "delete from mac_vod where vod_id = 1; -- trailing comment",
        "delete from mac_vod where vod_id = 1; # trailing comment",
        "delete from mac_vod where vod_id = 1; /* trailing */  ",
        "update mac_vod set vod_name = 'it''s;' where vod_id = 1",
        "update mac_vod set vod_name = 'a\\\\' where vod_id = 1;",
        "delete from mac_vod # ; drop\n where vod_id = 1",
    })
    void semicolonsInsideStringsOrCommentsAndOneTrailingSemicolonAreAllowed(String sql) {
        assertThat(prep(sql).verdict()).isEqualTo(Verdict.EXECUTE);
    }

    @Test
    void doubleDashNeedsWhitespaceToStartAComment() {
        // MySQL：-- 后必须跟空白或控制字符才是注释；1--1 是算术
        assertThat(prep("update mac_vod set vod_hits = 1--1; drop table mac_vod").verdict()).isEqualTo(Verdict.MULTI_STATEMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "drop table mac_vod",
        "TRUNCATE mac_vod",
        "alter table mac_vod add x int",
        "create table x (a int)",
        "replace into mac_vod (vod_id) values (1)",
        "with t as (select 1) delete from mac_vod",
        "(delete from mac_vod)",
        "/*!50000 drop */ table mac_vod",
        "update mac_vod set vod_hits = 1 /*!50000 , vod_name = 1 */",
        "rename table mac_vod to x",
        "grant all on *.* to x",
        "set @a = 1",
        "call p()",
        "lock tables mac_vod write",
    })
    void onlyInsertUpdateDeleteMayBeSent(String sql) {
        assertThat(prep(sql).verdict()).isEqualTo(Verdict.STRUCTURE_REFUSED);
    }

    @Test
    void leadingCommentsAreIgnoredForTheFirstKeyword() {
        assertThat(prep("/* a */ -- b\n # c\n  DeLeTe from mac_vod where vod_id = 1").verdict()).isEqualTo(Verdict.EXECUTE);
        assertThat(prep("/* a */ -- b\n # c\n  DeLeTe from mac_vod where vod_id = 1").sql())
                .isEqualTo("/* a */ -- b\n # c\n  DeLeTe from mac_vod where vod_id = 1");
    }

    @Test
    void inputOverOneMebibyteIsRefusedWithoutSending() {
        String big = "update mac_vod set vod_name = '" + "a".repeat(SqlStatements.MAX_SQL_BYTES) + "'";
        assertThat(prep(big).verdict()).isEqualTo(Verdict.STRUCTURE_REFUSED);
        String exact = "delete from mac_vod where 1=1 " + " ".repeat(SqlStatements.MAX_SQL_BYTES - 31) + "x";
        assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(SqlStatements.MAX_SQL_BYTES);
        assertThat(prep(exact).verdict()).isEqualTo(Verdict.EXECUTE);
    }

    @Test
    void singleRowInsertIsTheOnlyStatementWhoseServerErrorMeansNotApplied() {
        assertThat(prep("insert into mac_vod (vod_name) values ('a,(b)')").singleRow()).isTrue();
        assertThat(prep("INSERT INTO mac_vod VALUE (1, 'x')").singleRow()).isTrue();
        assertThat(prep("insert into mac_vod set vod_name = 'a'").singleRow()).isTrue();
        assertThat(prep("insert into mac_vod (vod_name) values ('a') on duplicate key update vod_hits = (1)").singleRow()).isTrue();
        assertThat(prep("insert into mac_vod (vod_name) values ('a'), ('b')").singleRow()).isFalse();
        assertThat(prep("insert into mac_vod (vod_name) (select 'x')").singleRow()).isFalse();
        assertThat(prep("update mac_vod set vod_hits = 1 where vod_id = 1").singleRow()).isFalse();
        assertThat(prep("delete from mac_vod where vod_id = 1").singleRow()).isFalse();
    }
}
