package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 插件包 install.sql 的切块与分类（B29，35 文档第 5 节）：只有数据语句可以通过，其他一律整包拒绝。 */
class AddonSqlTest {

    private static AddonSql.Analysis analyze(String sql) {
        return AddonSql.analyze(sql.getBytes(StandardCharsets.UTF_8), "mac_");
    }

    @Test
    void dataStatementsPassWithLegacyReplacements() {
        AddonSql.Analysis a = analyze("-- 注释\n/* 块注释开头的行跳过 */\n"
                + "insert into `__PREFIX__link` (`link_name`) VALUES ('a;b');\n"
                + "UPDATE __prefix__link SET link_sort = 1\n  WHERE link_id = 1;\n"
                + "DELETE FROM `__PREFIX__link` WHERE link_name = 'x''y';\n"
                + "REPLACE INTO __PREFIX__link (link_id) VALUES (9);\n"
                + "INSERT INTO __PREFIX__link (link_name) VALUES ('没有分号结尾，旧版不执行')");
        assertThat(a.ok()).isTrue();
        assertThat(a.statements()).containsExactly(
                "INSERT IGNORE INTO `mac_link` (`link_name`) VALUES ('a;b')",
                "UPDATE mac_link SET link_sort = 1\n  WHERE link_id = 1",
                "DELETE FROM `mac_link` WHERE link_name = 'x''y'",
                "REPLACE INTO mac_link (link_id) VALUES (9)");
    }

    @Test
    void emptyOrCommentOnlyFileHasNoStatements() {
        assertThat(analyze("").statements()).isEmpty();
        assertThat(analyze("-- only\n# hash;\n").ok()).isTrue();
    }

    @Test
    void structuralAndOtherStatementsAreRejected() {
        String[] bad = {
            "CREATE TABLE IF NOT EXISTS `__PREFIX__x` (`id` int) ENGINE=MyISAM;\n",
            "ALTER TABLE `__PREFIX__vod` ADD `x` int;\n",
            "DROP TABLE IF EXISTS `__PREFIX__x`;\n",
            "RENAME TABLE a TO b;\n",
            "TRUNCATE `__PREFIX__vod`;\n",
            "create index i on mac_vod (vod_name);\n",
            "SET FOREIGN_KEY_CHECKS=0;\n",
            "SELECT * FROM mac_admin INTO OUTFILE '/tmp/x';\n",
            "GRANT ALL ON *.* TO 'u'@'%';\n",
            "LOAD DATA INFILE '/etc/passwd' INTO TABLE mac_link;\n",
            "CALL p();\n",
            "LOCK TABLES mac_vod WRITE;\n",
        };
        for (String sql : bad) {
            assertThat(analyze(sql).ok()).as(sql).isFalse();
        }
        assertThat(analyze(bad[0]).rejected()).isEqualTo("CREATE");
        assertThat(analyze("SET FOREIGN_KEY_CHECKS=0;\n").rejected()).isEqualTo("SET");
    }

    @Test
    void anyRejectedStatementRejectsTheWholeFile() {
        AddonSql.Analysis a = analyze("INSERT INTO __PREFIX__link (link_name) VALUES ('a');\nDROP TABLE `__PREFIX__vod`;\n");
        assertThat(a.ok()).isFalse();
        assertThat(a.rejected()).isEqualTo("DROP");
    }

    @Test
    void secondStatementOnTheSameLineIsSeen() {
        // 旧版按行尾的 ; 切块、PDO::exec 会执行一块里的多条语句
        assertThat(analyze("INSERT INTO __PREFIX__link (link_name) VALUES ('a'); DROP TABLE mac_vod;\n").rejected()).isEqualTo("DROP");
        assertThat(analyze("INSERT INTO __PREFIX__link (link_name) VALUES ('a');DROP TABLE mac_vod;\n").rejected()).isEqualTo("DROP");
    }

    @Test
    void executableCommentsAreRejected() {
        assertThat(analyze("INSERT INTO mac_link (link_name) VALUES ('a') /*!50000 , ('b') */;\n").rejected()).isEqualTo("/*!");
        assertThat(analyze("  /*!40101 SET NAMES utf8 */;\n").rejected()).isEqualTo("/*!");
    }

    @Test
    void multiLineCommentContinuationIsNotData() {
        // 旧版只跳过以 /* 开头的那一行，注释的后续行会拼进下一块（MySQL 报语法错、旧版忽略）；新版视为不能执行
        assertThat(analyze("/*\n Navicat dump\n*/\nINSERT INTO __PREFIX__link (link_name) VALUES ('a');\n").ok()).isFalse();
    }

    @Test
    void semicolonsInsideQuotesAndCommentsDoNotSplit() {
        AddonSql.Analysis a = analyze("INSERT INTO __PREFIX__link (link_name, link_url) VALUES (\"x;y\", `a;b`) -- c;d\n;\n");
        assertThat(a.ok()).isTrue();
        assertThat(a.statements()).hasSize(1);
        assertThat(analyze("UPDATE mac_link SET link_name = 'it\\'s; DROP TABLE x' WHERE link_id = 1;\n").ok()).isTrue();
    }
}
