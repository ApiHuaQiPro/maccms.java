package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.install.application.InstallSqlFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * APPK-2 S3-20 C-57 / V-25：install.sql 的 SHA-256 常量与仓库文件一致、工作区为 LF，以及按 PHP {@code mac_parse_sql} 的切分。
 */
class InstallSqlFileTest {

    static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");

    @Test
    void constantEqualsRepositoryFileAndFileIsLf() throws Exception {
        byte[] bytes = Files.readAllBytes(INSTALL_SQL);
        assertThat(bytes).as("install.sql 含 \\r：工作区按 CRLF 检出，哈希会变成 152e5424…；"
                + "按 .gitattributes（C-67）重新检出该文件（删除后 git checkout）").doesNotContain((byte) '\r');
        assertThat(bytes).hasSize(45049);
        assertThat(InstallSqlFile.sha256(bytes)).isEqualTo(InstallSqlFile.INSTALL_SQL_SHA256)
                .isEqualTo("48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4");
        assertThat(InstallSqlFile.matches(bytes)).isTrue();
    }

    @Test
    void oneByteDifferenceOrCrlfDoesNotMatch() throws Exception {
        byte[] bytes = Files.readAllBytes(INSTALL_SQL);
        byte[] changed = bytes.clone();
        changed[changed.length - 1] ^= 1;
        assertThat(InstallSqlFile.matches(changed)).isFalse();
        byte[] crlf = new String(bytes, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        assertThat(InstallSqlFile.sha256(crlf)).startsWith("152e5424");
        assertThat(InstallSqlFile.matches(crlf)).isFalse();
        assertThat(InstallSqlFile.matches(null)).isFalse();
    }

    @Test
    void repositoryFileSplitsIntoDropCreateAndSeedStatements() throws Exception {
        List<String> sql = InstallSqlFile.statements(Files.readAllBytes(INSTALL_SQL));
        // 26 张表：每张一条 DROP TABLE IF EXISTS、一条 CREATE TABLE；mac_group 另有 3 条默认会员组 INSERT
        assertThat(sql.stream().filter(s -> s.startsWith("DROP TABLE IF EXISTS `mac_"))).hasSize(26);
        assertThat(sql.stream().filter(s -> s.startsWith("CREATE TABLE `mac_"))).hasSize(26);
        assertThat(sql.stream().filter(s -> s.startsWith("INSERT INTO `mac_group`"))).hasSize(3);
        assertThat(sql).hasSize(26 * 2 + 3);
        assertThat(sql).noneMatch(s -> s.startsWith("--") || s.isBlank() || s.contains("\r"));
        assertThat(sql).noneMatch(s -> s.toUpperCase(java.util.Locale.ROOT).contains("CREATE DATABASE"));
    }

    @Test
    void parseFollowsMacParseSql() {
        String text = "\n-- comment\n# hash comment\n/* one line */\n/*\nmulti\n*/\nBEGIN;\nDROP TABLE IF EXISTS `mac_a`;\n"
                + "CREATE TABLE `mac_a` (\n  `id` int\n);\nCOMMIT;\n\nINSERT INTO `mac_a` VALUES (1);\n";
        assertThat(InstallSqlFile.statements(text.getBytes(StandardCharsets.UTF_8))).containsExactly(
                "DROP TABLE IF EXISTS `mac_a`",
                "CREATE TABLE `mac_a` (\n  `id` int\n)",
                // PHP 先 trim 整段再按 ";\n" 切分，最后一条保留末尾分号（原样交给数据库）
                "INSERT INTO `mac_a` VALUES (1);");
        // CRLF 与 CR 按 PHP 换成 LF 后再切分
        assertThat(InstallSqlFile.statements("SELECT 1;\r\nSELECT 2;\rSELECT 3".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("SELECT 1", "SELECT 2", "SELECT 3");
        assertThat(InstallSqlFile.statements(new byte[0])).isEmpty();
    }
}
