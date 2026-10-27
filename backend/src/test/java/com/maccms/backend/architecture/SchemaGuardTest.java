package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 表结构护栏的静态部分（B02，路线图第 1.2 节；docs/architecture/11-guards-and-legacy-config.md 第 3 节）：新系统任何入口都不执行
 * DDL、表维护或账号语句，也不带任何自动迁移/初始化脚本。运行期部分（{@code -Pmysql-it} 结束时比对 information_schema）
 * 见 {@code com.maccms.backend.schema.SchemaGuardMySqlIT}。
 *
 * <p>扫描范围：src/main/java（去掉注释、保留字符串，并把相邻字面量的 {@code " + "} 拼接合并后再匹配）、src/main/resources
 * 下的全部文本文件（XML 去掉注释）、pom.xml 的依赖。规则由 {@link #rulesOnSyntheticSources()} 自检。
 */
class SchemaGuardTest {

    /**
     * 结构语句（CREATE/ALTER/DROP/RENAME/TRUNCATE 及其对象）、表维护（OPTIMIZE/REPAIR/ANALYZE/CHECK TABLE、旧后台"数据库工具"的四项，
     * D10）、账号与权限（GRANT/REVOKE、CREATE/ALTER/DROP USER）、导入（LOAD DATA、SOURCE）。不区分大小写，关键字之间允许任意空白。
     */
    static final Pattern DDL = Pattern.compile("(?i)\\b("
            + "(?:CREATE|ALTER|DROP)\\s+(?:OR\\s+REPLACE\\s+|TEMPORARY\\s+|UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+"
            + "|ONLINE\\s+|OFFLINE\\s+|IGNORE\\s+|DEFINER\\s*=\\s*\\S+\\s+|ALGORITHM\\s*=\\s*\\w+\\s+|SQL\\s+SECURITY\\s+\\w+\\s+)*"
            + "(?:TABLE|TABLES|DATABASE|SCHEMA|INDEX|VIEW|TRIGGER|PROCEDURE|FUNCTION|EVENT|USER|ROLE|SERVER|TABLESPACE"
            + "|LOGFILE|SPATIAL\\s+REFERENCE\\s+SYSTEM|RESOURCE\\s+GROUP)"
            + "|RENAME\\s+(?:TABLE|USER|DATABASE)"
            + "|TRUNCATE\\s+(?:TABLE\\s+)?(?:`|\\w*_\\w*)"
            + "|(?:OPTIMIZE|REPAIR|ANALYZE|CHECK|CHECKSUM)\\s+(?:NO_WRITE_TO_BINLOG\\s+|LOCAL\\s+)?TABLE"
            + "|GRANT\\s+[^;'\"]{1,200}?\\s+(?:ON|TO)\\s|REVOKE\\s+[^;'\"]{1,200}?\\s+(?:ON|FROM)\\s|SET\\s+PASSWORD"
            + "|LOAD\\s+DATA|LOAD\\s+XML|IMPORT\\s+TABLE|SOURCE\\s+\\S+\\.sql"
            + ")");

    /**
     * 登记的例外（B15，MACC-39；路线图 B15 行，docs/architecture/24-b15-admin-system.md 第 9.3 节）：数据库工具"备份导出"把建表语句写进备份文件，
     * 格式与旧版相同——{@code SHOW CREATE TABLE} 读出建表语句文本、取结果里的 {@code Create Table} 列，再在文件里写一行 {@code DROP TABLE IF EXISTS}。
     * 这些只是写进文件的文字，新系统从不执行备份文件（还原备份在入口拒绝），{@code DatabaseToolMySqlIT} 断言导出期间只发出 SHOW/SELECT。
     * 每一项按"文件 → 匹配到的文字"逐个消耗，多出现一次就是违规。
     */
    static final Map<String, List<String>> REGISTERED_TEXT = Map.of(
            "com/maccms/backend/admin/application/DatabaseToolService.java", List.of("Create Table", "DROP TABLE"),
            "mapper/admin/DatabaseToolMapper.xml", List.of("CREATE TABLE"));

    /** 去掉 {@link #REGISTERED_TEXT} 为该文件登记的例外（每项只抵消一次）。 */
    static List<String> withoutRegistered(String file, List<String> violations) {
        List<String> allowed = new ArrayList<>(REGISTERED_TEXT.getOrDefault(file, List.of()));
        List<String> out = new ArrayList<>();
        for (String v : violations) {
            String hit = v.substring(v.indexOf('：') + 1);
            if (!allowed.remove(hit)) {
                out.add(v);
            }
        }
        return out;
    }

    /** 相邻字符串字面量的拼接（"a " + "b"），合并后再匹配，防止把关键字拆成两段绕过。 */
    private static final Pattern LITERAL_CONCAT = Pattern.compile("\"\\s*\\+\\s*\"");

    /** pom.xml 中不允许的自动迁移/ORM 建表依赖。 */
    private static final List<String> FORBIDDEN_ARTIFACTS = List.of("flyway-core", "flyway-mysql",
            "spring-boot-starter-flyway", "liquibase-core", "spring-boot-starter-liquibase",
            "spring-boot-starter-data-jpa", "hibernate-core", "spring-boot-starter-data-jdbc",
            "spring-boot-starter-data-r2dbc", "mybatis-plus-boot-starter");

    @Test
    void mainJavaContainsNoDdl() throws IOException {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : JavaSources.mainJava().entrySet()) {
            for (String v : withoutRegistered(e.getKey(), javaViolations(e.getValue()))) {
                all.add(e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("src/main/java 中出现结构/维护/账号语句").isEmpty();
    }

    @Test
    void mainResourcesContainNoDdlAndNoSqlScripts() throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(JavaSources.MAIN_RESOURCES)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = JavaSources.MAIN_RESOURCES.relativize(p).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".sql") || lower.contains("db/migration") || lower.contains("db/changelog")) {
                    all.add(rel + ": 不允许 SQL 脚本或迁移目录");
                    continue;
                }
                if (!isText(lower)) {
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (lower.endsWith(".xml")) {
                    text = JavaSources.stripXmlComments(text);
                } else if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")) {
                    text = text.replaceAll("(?m)#.*$", " ");
                    for (String key : List.of("flyway", "liquibase", "ddl-auto", "hbm2ddl", "schema-locations",
                            "data-locations", "initialize-schema")) {
                        if (text.contains(key)) {
                            all.add(rel + ": 不允许配置 " + key);
                        }
                    }
                }
                for (String v : withoutRegistered(rel, textViolations(text))) {
                    all.add(rel + ": " + v);
                }
            }
        }
        assertThat(all).as("src/main/resources 中出现结构语句、SQL 脚本或自动迁移配置").isEmpty();
        String yml = Files.readString(JavaSources.MAIN_RESOURCES.resolve("application.yml"), StandardCharsets.UTF_8);
        assertThat(yml).contains("mode: never");
    }

    @Test
    void pomHasNoMigrationOrSchemaGeneratingDependencies() throws IOException {
        String pom = JavaSources.stripXmlComments(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
        Matcher m = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>").matcher(pom);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (FORBIDDEN_ARTIFACTS.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void rulesOnSyntheticSources() {
        assertThat(javaViolations("""
                package com.maccms.backend.content.infrastructure;
                // DROP TABLE mac_vod 写在注释里不算
                /* ALTER TABLE mac_vod ADD x INT */
                interface Ok {
                    String SELECT = "SELECT vod_id FROM mac_vod WHERE vod_status = 1";
                    String WORDS = "create a table of contents, drop the user's shoes, check the table, truncate the text, "
                            + "grant access";
                    void dropTable();
                }
                """)).isEmpty();
        assertThat(textViolations("SELECT CHECKSUM(x) FROM t; UPDATE mac_vod SET vod_hits = vod_hits + 1")).isEmpty();

        for (String bad : List.of(
                "CREATE TABLE mac_x (id INT)", "create table if not exists mac_x(id int)", "CREATE TEMPORARY TABLE t (a INT)",
                "ALTER TABLE mac_vod ADD COLUMN x INT", "alter\n  table mac_vod engine=InnoDB", "DROP TABLE mac_vod",
                "DROP TABLE IF EXISTS `mac_vod`", "RENAME TABLE a TO b", "TRUNCATE mac_vod", "TRUNCATE TABLE `mac_vod`",
                "CREATE INDEX i ON mac_vod (vod_name)", "CREATE UNIQUE INDEX i ON t (a)", "CREATE FULLTEXT INDEX f ON t (a)",
                "DROP INDEX i ON mac_vod", "CREATE DATABASE maccms", "DROP DATABASE maccms", "CREATE SCHEMA s",
                "CREATE OR REPLACE VIEW v AS SELECT 1", "CREATE DEFINER=`root`@`%` TRIGGER t BEFORE UPDATE ON x",
                "CREATE PROCEDURE p() BEGIN END", "CREATE EVENT e ON SCHEDULE EVERY 1 DAY DO SELECT 1",
                "OPTIMIZE TABLE mac_vod", "REPAIR TABLE mac_vod", "ANALYZE TABLE mac_vod", "CHECK TABLE mac_vod",
                "OPTIMIZE NO_WRITE_TO_BINLOG TABLE mac_vod", "GRANT SELECT ON *.* TO 'u'", "REVOKE ALL ON x FROM y",
                "CREATE USER 'u'@'%'", "ALTER USER 'u' IDENTIFIED BY 'x'", "SET PASSWORD FOR 'u' = 'x'",
                "LOAD DATA INFILE 'x' INTO TABLE t", "SOURCE install.sql")) {
            assertThat(textViolations(bad)).as(bad).isNotEmpty();
            assertThat(javaViolations("package com.maccms.backend.admin.infrastructure;\nclass X { String s = \""
                    + bad.replace("\n", "\\n").replace("\"", "\\\"") + "\"; }")).as(bad).isNotEmpty();
        }
        // 拆成多个字面量拼接同样能抓到；文本块、MyBatis 注解里的同样能抓到
        assertThat(javaViolations("package com.maccms.backend.x;\nclass X { String s = \"ALTER \" + \"TABLE mac_vod\"; }"))
                .isNotEmpty();
        assertThat(javaViolations("package com.maccms.backend.x;\nclass X { String s = \"\"\"\n  DROP\n  TABLE t\n  \"\"\"; }"))
                .isNotEmpty();
        assertThat(javaViolations("package com.maccms.backend.x.infrastructure;\ninterface M {\n"
                + "  @org.apache.ibatis.annotations.Update(\"TRUNCATE TABLE mac_vod\") void wipe();\n}")).isNotEmpty();
    }

    static List<String> javaViolations(String source) {
        String text = LITERAL_CONCAT.matcher(JavaSources.stripComments(source)).replaceAll("");
        // 字面量里的 \n、\t 等转义在运行时是空白
        return textViolations(text.replaceAll("\\\\[ntrfs]", " "));
    }

    static List<String> textViolations(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = DDL.matcher(text);
        while (m.find()) {
            out.add("结构/维护/账号语句：" + m.group(1).replaceAll("\\s+", " "));
        }
        return out;
    }

    private static boolean isText(String lowerName) {
        return Stream.of(".xml", ".yml", ".yaml", ".properties", ".txt", ".json", ".jte", ".html", ".js")
                .anyMatch(lowerName::endsWith);
    }
}
