package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

import com.maccms.backend.common.write.WriteOperation;
import com.maccms.backend.common.write.WritePermission;
import com.maccms.backend.common.write.WriteRegistry;
import com.maccms.backend.common.write.WriteRejectedException;
import com.maccms.backend.common.write.WriteStatement;
import com.maccms.backend.writedrill.DrillRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 写入护栏的静态部分（B07，路线图第 5.3 节 B07 ②③；docs/architecture/12-write-infrastructure.md 第 3、4 节）。运行期部分是
 * {@code common.write.WriteGuardInterceptor}（语句执行前按同一份登记表检查实际 SQL）。
 * <ol>
 * <li>写 SQL 只能写在 {@code src/main/resources/mapper-write/<module>/*.xml}：Java 源码里不得出现对 {@code mac_*} 表的
 * INSERT/UPDATE/DELETE/REPLACE 文本，不得用 MyBatis 的 {@code @Insert/@Update/@Delete/@SelectKey} 注解；
 * 只读 Mapper XML 不得有写语句（{@code ReadOnlyGuardrailsTest}）。</li>
 * <li>写 Mapper XML 的每条语句：命名空间是 {@code <module>.infrastructure} 下标了 {@code @WriteMapper} 的接口，文件位于
 * {@code mapper-write/<module>/}；元素只能是 insert/update/delete（不得有 select、where、sql/include、selectKey、{@code ${}}）；
 * SQL 符合 {@link WriteStatement} 规范，元素类型与 SQL 一致，"模块 + 表 + 操作"已在 {@link WriteRegistry} 登记。</li>
 * <li>登记表的每一条都符合路线图写入方矩阵 {@link #OWNER_MATRIX}（表与批次）。</li>
 * <li>可写数据源的入口只在 {@code common.write}：{@code WriteDatabase.open}、{@code WriteRegistry.of}、连接池与 MyBatis 会话工厂类型、
 * {@code maccms.write} 配置；其他包只有 {@code <module>.infrastructure} 可以引用 {@code WriteDatabase}。</li>
 * <li>不使用事务注解与事务模板：旧表是 MyISAM，事务不起作用，多表一致性用 {@code WriteSequence}。</li>
 * </ol>
 * 规则由 {@link #rulesOnSyntheticSources()} 自检；测试侧的演练 Mapper（{@code mapper-write/drill}）在演练登记表下只剩两条故意
 * 未登记的语句，在正式登记表下全部被拦下（{@link #drillMappersAreCheckedLikeMainOnes()}）。
 */
class WriteGuardTest {

    static final Path WRITE_MAPPERS = JavaSources.MAIN_RESOURCES.resolve("mapper-write");
    private static final Path TEST_JAVA = Path.of("src", "test", "java");
    private static final Path TEST_WRITE_MAPPERS = Path.of("src", "test", "resources", "mapper-write");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");

    private static final Set<String> CONTENT = Set.of("B11", "B13", "B19", "B20");

    /** 路线图第 5.3 节"写入方矩阵"：表 → 可以登记写入的批次。改矩阵须先改路线图。 */
    static final Map<String, Set<String>> OWNER_MATRIX = Map.ofEntries(
            entry("mac_user", Set.of("B09", "B10", "B14", "B24", "B25", "B26")),
            entry("mac_group", Set.of("B14")),
            entry("mac_order", Set.of("B10", "B14", "B24")),
            entry("mac_card", Set.of("B10", "B14")),
            entry("mac_cash", Set.of("B10", "B14")),
            entry("mac_plog", Set.of("B10", "B14", "B24")),
            entry("mac_ulog", Set.of("B10", "B14")),
            entry("mac_visit", Set.of("B10", "B14")),
            entry("mac_msg", Set.of("B25")),
            entry("mac_comment", Set.of("B11", "B13", "B19")),
            entry("mac_gbook", Set.of("B11", "B13")),
            entry("mac_vod", CONTENT),
            entry("mac_art", CONTENT),
            entry("mac_topic", CONTENT),
            entry("mac_actor", CONTENT),
            entry("mac_role", CONTENT),
            entry("mac_website", CONTENT),
            entry("mac_link", Set.of("B13", "B19", "B20")),
            entry("mac_type", Set.of("B13", "B19", "B20", "B22")),
            entry("mac_vod_search", Set.of("B11", "B12", "B19")),
            entry("mac_admin", Set.of("B12", "B22")),
            entry("mac_annex", Set.of("B13")),
            entry("mac_collect", Set.of("B17")),
            entry("mac_cj_node", Set.of("B17")),
            entry("mac_cj_content", Set.of("B20")),
            entry("mac_cj_history", Set.of("B20")));

    /** Java 源码中（含字符串）对 mac_* 表的写语句文本。 */
    private static final Pattern WRITE_SQL_TEXT = Pattern.compile("(?i)\\b("
            + "INSERT\\s+(?:(?:LOW_PRIORITY|DELAYED|HIGH_PRIORITY)\\s+)?(?:IGNORE\\s+)?INTO\\s+`?mac_"
            + "|UPDATE\\s+(?:LOW_PRIORITY\\s+)?(?:IGNORE\\s+)?`?mac_\\w+`?(?:\\s+(?:AS\\s+)?\\w+)?\\s+SET\\b"
            + "|DELETE\\s+(?:(?:LOW_PRIORITY|QUICK|IGNORE)\\s+)*FROM\\s+`?mac_"
            + "|REPLACE\\s+(?:(?:LOW_PRIORITY|DELAYED)\\s+)?(?:INTO\\s+)?`?mac_)");
    /** 代码中（不含注释与字符串）不允许的写注解与事务用法。 */
    private static final Pattern FORBIDDEN_CODE = Pattern.compile("("
            + "\\borg\\.apache\\.ibatis\\.annotations\\.(?:Insert|Update|Delete|SelectKey|Options)\\b"
            + "|@\\s*(?:Insert|Update|Delete|SelectKey)\\s*\\("
            + "|\\bTransactional\\b|\\bTransactionTemplate\\b|\\bPlatformTransactionManager\\b|\\bTransactionManager\\b"
            + "|\\.\\s*setAutoCommit\\s*\\(\\s*false\\b)");
    /** 只能出现在 common.write 的入口（代码）。 */
    private static final Pattern WRITE_ENTRY_CODE = Pattern.compile("("
            + "\\bWriteDatabase\\s*\\.\\s*open\\b|\\bWriteRegistry\\s*\\.\\s*of\\b|\\bcom\\.zaxxer\\.hikari\\b"
            + "|\\bHikari(?:DataSource|Config)\\b|\\bSqlSessionFactoryBean\\b|\\bSqlSessionTemplate\\b"
            + "|\\bSqlSessionFactory\\b|\\bWriteGuardInterceptor\\b)");
    /** 只能出现在 common.write 的配置键（含字符串）。 */
    private static final Pattern WRITE_CONFIG_TEXT = Pattern.compile("(maccms\\.write\\b|MACCMS_WRITE_)");
    private static final Pattern WRITE_MAPPER_ANNOTATION = Pattern.compile(
            "@\\s*(?:com\\.maccms\\.backend\\.common\\.write\\.)?WriteMapper\\b");
    private static final Pattern MYBATIS_MAPPER_ANNOTATION = Pattern.compile(
            "@\\s*(?:org\\.apache\\.ibatis\\.annotations\\.)?Mapper\\b");
    private static final Pattern INFRASTRUCTURE_PKG = Pattern.compile("[a-z][a-z0-9]*\\.infrastructure(?:\\.[a-z][a-z0-9_]*)*");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Z]\\w*)");

    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern STATEMENT = Pattern.compile(
            "<(insert|update|delete)\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>(.*?)</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern FORBIDDEN_ELEMENT = Pattern.compile("<(select|where|sql|include|selectKey|cache|cache-ref)\\b");
    private static final Pattern TAG = Pattern.compile("<(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");
    private static final Pattern STATEMENT_ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]*)\"");

    @Test
    void ownerMatrixCoversExactlyTheLegacyTables() throws IOException {
        Matcher m = Pattern.compile("CREATE TABLE `(mac_[a-z_]+)`").matcher(
                Files.readString(INSTALL_SQL, StandardCharsets.UTF_8));
        Set<String> tables = new TreeSet<>();
        while (m.find()) {
            tables.add(m.group(1));
        }
        assertThat(tables).hasSize(26);
        assertThat(new TreeSet<>(OWNER_MATRIX.keySet())).isEqualTo(tables);
    }

    @Test
    void registeredPermissionsFollowTheOwnerMatrix() {
        assertThat(registryViolations(WriteRegistry.registered().permissions(), false)).isEmpty();
        // B07、B08 不登记业务写入；B09（MACC-33）登记会员模块对 mac_user 的插入与更新（18 文档第 6.1 节）；
        // B10（MACC-34）登记会员模块对 mac_ulog/mac_plog/mac_card/mac_cash/mac_order/mac_visit 的写入（19 文档第 6.1 节）；
        // B12（MACC-36）登记后台模块对 mac_admin 的写入与后台"清理缓存"对 mac_vod_search 的删除（21 文档第 5 节）；
        // B13（MACC-37）登记后台内容管理：内容表的新增与删除、友情链接/分类/附件的增删改、评论删除与留言修改删除（22 文档第 6 节）
        // B14（MACC-38）登记后台会员与财务：会员删除、会员组增删改、卡密生成与删除、订单删除、提现审核、推广访问删除（23 文档第 6 节）
        assertThat(WriteRegistry.registered().permissions()).extracting(p -> p.module() + " " + p.table() + " "
                + p.operation() + " " + p.batch())
                .containsExactly("member mac_user INSERT B09", "member mac_user UPDATE B09",
                        "member mac_ulog INSERT B10", "member mac_ulog UPDATE B10", "member mac_ulog DELETE B10",
                        "member mac_plog INSERT B10", "member mac_plog DELETE B10", "member mac_card UPDATE B10",
                        "member mac_cash INSERT B10", "member mac_cash DELETE B10", "member mac_order INSERT B10",
                        "member mac_visit INSERT B10", "content mac_vod UPDATE B11", "content mac_art UPDATE B11",
                        "content mac_topic UPDATE B11", "content mac_actor UPDATE B11", "content mac_role UPDATE B11",
                        "content mac_website UPDATE B11", "content mac_vod_search INSERT B11",
                        "content mac_vod_search UPDATE B11", "comment mac_comment INSERT B11",
                        "comment mac_comment UPDATE B11", "comment mac_gbook INSERT B11", "admin mac_admin INSERT B12",
                        "admin mac_admin UPDATE B12", "admin mac_admin DELETE B12", "content mac_vod_search DELETE B12",
                        "content mac_vod INSERT B13", "content mac_vod DELETE B13", "content mac_art INSERT B13",
                        "content mac_art DELETE B13", "content mac_topic INSERT B13", "content mac_topic DELETE B13",
                        "content mac_actor INSERT B13", "content mac_actor DELETE B13", "content mac_role INSERT B13",
                        "content mac_role DELETE B13", "content mac_website INSERT B13", "content mac_website DELETE B13",
                        "content mac_link INSERT B13", "content mac_link UPDATE B13", "content mac_link DELETE B13",
                        "content mac_type INSERT B13", "content mac_type UPDATE B13", "content mac_type DELETE B13",
                        "content mac_annex INSERT B13", "content mac_annex UPDATE B13", "content mac_annex DELETE B13",
                        "comment mac_comment DELETE B13", "comment mac_gbook UPDATE B13", "comment mac_gbook DELETE B13",
                        "member mac_user DELETE B14", "member mac_group INSERT B14", "member mac_group UPDATE B14",
                        "member mac_group DELETE B14", "member mac_card INSERT B14", "member mac_card DELETE B14",
                        "member mac_order DELETE B14", "member mac_cash UPDATE B14", "member mac_visit DELETE B14");
    }

    @Test
    void writeSqlLivesOnlyInRegisteredWriteMapperXml() throws IOException {
        Map<String, String> java = JavaSources.mainJava();
        Set<String> writeMappers = writeMapperTypes(java.values());
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : java.entrySet()) {
            for (String v : javaViolations(e.getValue())) {
                all.add(e.getKey() + ": " + v);
            }
        }
        for (Map.Entry<String, String> e : JavaSources.read(WRITE_MAPPERS, ".xml").entrySet()) {
            for (String v : xmlViolations(e.getKey(), e.getValue(), WriteRegistry.registered(), writeMappers)) {
                all.add("mapper-write/" + e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("写入护栏").isEmpty();
    }

    @Test
    void applicationYmlKeepsWritesOffAndReadPoolReadOnly() throws IOException {
        String yml = Files.readString(JavaSources.MAIN_RESOURCES.resolve("application.yml"), StandardCharsets.UTF_8);
        assertThat(yml).contains("read-only: true", "enabled: ${MACCMS_WRITE_ENABLED:false}",
                "url: ${MACCMS_WRITE_DB_URL:}", "username: ${MACCMS_WRITE_DB_USERNAME:}",
                "password: ${MACCMS_WRITE_DB_PASSWORD:}", "allow-root: ${MACCMS_WRITE_DB_ALLOW_ROOT:false}");
    }

    /** 测试侧演练 Mapper 与正式 Mapper 走同一套检查。 */
    @Test
    void drillMappersAreCheckedLikeMainOnes() throws IOException {
        Map<String, String> testJava = JavaSources.read(TEST_JAVA, ".java");
        Set<String> drillTypes = writeMapperTypes(testJava.values());
        assertThat(drillTypes).containsExactlyInAnyOrder("com.maccms.backend.drill.infrastructure.DrillWriteMapper",
                "com.maccms.backend.drillother.infrastructure.OtherWriteMapper");
        Map<String, String> xml = JavaSources.read(TEST_WRITE_MAPPERS, ".xml");
        assertThat(xml).containsOnlyKeys("drill/DrillWriteMapper.xml", "drillother/OtherWriteMapper.xml");

        List<String> underDrill = new ArrayList<>();
        List<String> underProduction = new ArrayList<>();
        for (Map.Entry<String, String> e : xml.entrySet()) {
            underDrill.addAll(xmlViolations(e.getKey(), e.getValue(), DrillRegistry.registry(), drillTypes));
            underProduction.addAll(xmlViolations(e.getKey(), e.getValue(), WriteRegistry.registered(), drillTypes));
        }
        // 演练登记表下只剩故意未登记的三条：表未登记、操作未登记、模块未登记
        assertThat(underDrill).hasSize(3)
                .anySatisfy(v -> assertThat(v).contains("insertGbook").contains("mac_gbook"))
                .anySatisfy(v -> assertThat(v).contains("deleteUser").contains("DELETE"))
                .anySatisfy(v -> assertThat(v).contains("addPoints").contains("drillother"));
        long statements = xml.values().stream().mapToLong(s -> STATEMENT.matcher(s).results().count()).sum();
        assertThat(underProduction).hasSize((int) statements);
        assertThat(registryViolations(DrillRegistry.registry().permissions(), true)).isEmpty();
        assertThat(registryViolations(DrillRegistry.registry().permissions(), false)).as("演练条目不能进正式登记表")
                .hasSize(DrillRegistry.registry().permissions().size());
    }

    /** 回滚演练工具只用 DML：不得出现结构语句、表维护语句（含 TRUNCATE）。 */
    @Test
    void drillToolsUseOnlyDml() throws IOException {
        Map<String, String> drill = JavaSources.read(TEST_JAVA.resolve(Path.of("com", "maccms", "backend", "writedrill")),
                ".java");
        assertThat(drill).containsKeys("DmlRestore.java", "TableSnapshot.java", "SnapshotDiff.java");
        for (String name : List.of("DmlRestore.java", "TableSnapshot.java", "SnapshotDiff.java", "DatabaseSnapshot.java")) {
            assertThat(SchemaGuardTest.javaViolations(drill.get(name))).as(name).isEmpty();
        }
    }

    @Test
    void rulesOnSyntheticSources() {
        WriteRegistry registry = WriteRegistry.of(List.of(
                new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B09"),
                new WritePermission("member", "mac_plog", WriteOperation.INSERT, "B10")));
        Set<String> types = Set.of("com.maccms.backend.member.infrastructure.UserWriteMapper");
        String ok = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.maccms.backend.member.infrastructure.UserWriteMapper">
                  <!-- <select id="x">SELECT 1</select> 注释里的不算 -->
                  <update id="deduct">
                    UPDATE mac_user SET user_points = user_points - #{points}
                    WHERE user_id = #{userId} AND user_points &gt;= #{points}
                    <if test="groupId != null">AND group_id = #{groupId}</if>
                  </update>
                  <insert id="log" useGeneratedKeys="true" keyProperty="plogId">
                    INSERT INTO mac_plog (user_id, plog_points) VALUES (#{userId}, #{points})
                  </insert>
                </mapper>
                """;
        assertThat(xmlViolations("member/UserWriteMapper.xml", ok, registry, types)).isEmpty();

        // 未登记的表、操作、模块；以及各种不符合规范的写法
        Map<String, String> bad = Map.ofEntries(
                entry("表未登记", "<insert id=\"a\">INSERT INTO mac_gbook (gbook_name) VALUES (#{n})</insert>"),
                entry("操作未登记", "<delete id=\"a\">DELETE FROM mac_user WHERE user_id = #{id}</delete>"),
                entry("元素与 SQL 不一致", "<update id=\"a\">DELETE FROM mac_user WHERE user_id = #{id}</update>"),
                entry("select", "<select id=\"a\">SELECT user_id FROM mac_user</select>"),
                entry("where 标签", "<update id=\"a\">UPDATE mac_user SET user_points = 0 <where>user_id = #{id}</where></update>"),
                entry("无 WHERE", "<update id=\"a\">UPDATE mac_user SET user_points = 0</update>"),
                entry("sql 片段", "<sql id=\"c\">user_id</sql>"),
                entry("include", "<update id=\"a\">UPDATE mac_user SET user_points = 0 WHERE <include refid=\"c\"/></update>"),
                entry("dollar", "<update id=\"a\">UPDATE mac_user SET user_points = 0 WHERE user_id = ${id}</update>"),
                entry("selectKey", "<insert id=\"a\"><selectKey keyProperty=\"id\">SELECT 1</selectKey>"
                        + "INSERT INTO mac_plog (user_id) VALUES (1)</insert>"),
                entry("多语句", "<update id=\"a\">UPDATE mac_user SET user_points = 0 WHERE user_id = 1; "
                        + "UPDATE mac_user SET user_points = 0 WHERE user_id = 2</update>"),
                entry("多表", "<update id=\"a\">UPDATE mac_user u JOIN mac_card c ON c.user_id = u.user_id "
                        + "SET u.user_points = 0 WHERE c.card_id = 1</update>"),
                entry("REPLACE", "<insert id=\"a\">REPLACE INTO mac_plog (plog_id) VALUES (1)</insert>"),
                entry("IGNORE", "<insert id=\"a\">INSERT IGNORE INTO mac_plog (user_id) VALUES (1)</insert>"));
        for (Map.Entry<String, String> e : bad.entrySet()) {
            String xml = "<mapper namespace=\"com.maccms.backend.member.infrastructure.UserWriteMapper\">" + e.getValue()
                    + "</mapper>";
            assertThat(xmlViolations("member/UserWriteMapper.xml", xml, registry, types)).as(e.getKey()).isNotEmpty();
        }
        // 模块未登记 / 命名空间不在 infrastructure / 不是 @WriteMapper 接口 / 文件目录与模块不符
        String adminXml = "<mapper namespace=\"com.maccms.backend.admin.infrastructure.UserWriteMapper\">"
                + "<update id=\"a\">UPDATE mac_user SET user_points = 0 WHERE user_id = #{id}</update></mapper>";
        assertThat(xmlViolations("admin/UserWriteMapper.xml", adminXml, registry,
                Set.of("com.maccms.backend.admin.infrastructure.UserWriteMapper"))).isNotEmpty();
        String appXml = "<mapper namespace=\"com.maccms.backend.member.application.UserWriteMapper\">"
                + "<update id=\"a\">UPDATE mac_user SET user_points = 0 WHERE user_id = #{id}</update></mapper>";
        assertThat(xmlViolations("member/UserWriteMapper.xml", appXml, registry,
                Set.of("com.maccms.backend.member.application.UserWriteMapper"))).isNotEmpty();
        assertThat(xmlViolations("member/UserWriteMapper.xml", ok, registry, Set.of())).isNotEmpty();
        assertThat(xmlViolations("admin/UserWriteMapper.xml", ok, registry, types)).isNotEmpty();

        // Java：写 SQL 文本、写注解、事务、入口与配置越界
        assertThat(javaViolations("""
                package com.maccms.backend.member.infrastructure;
                // UPDATE mac_user SET user_points = 0 写在注释里不算
                @com.maccms.backend.common.write.WriteMapper
                interface UserWriteMapper { int deduct(int userId, int points); }
                class X { String s = "update the user's settings"; String t = "SELECT user_id FROM mac_user"; }
                class Y { void f(com.maccms.backend.common.write.WriteDatabase db) { db.mapper(UserWriteMapper.class); } }
                """)).isEmpty();
        for (String b : List.of(
                "class X { String s = \"UPDATE mac_user SET user_points = 0 WHERE user_id = ?\"; }",
                "class X { String s = \"update `mac_user` u set u.user_points = 0\"; }",
                "class X { String s = \"INSERT INTO mac_plog (user_id) VALUES (?)\"; }",
                "class X { String s = \"insert ignore into mac_plog values ()\"; }",
                "class X { String s = \"DELETE FROM mac_user WHERE user_id = ?\"; }",
                "class X { String s = \"REPLACE INTO mac_user (user_id) VALUES (1)\"; }",
                "class X { String s = \"DELETE \" + \"FROM mac_user WHERE 1\"; }",
                "class X { String s = \"\"\"\n  UPDATE mac_vod\n  SET vod_hits = 0 WHERE vod_id = 1\n  \"\"\"; }",
                "interface M { @org.apache.ibatis.annotations.Update(\"x\") int u(); }",
                "import org.apache.ibatis.annotations.Insert;",
                "interface M { @Delete(\"x\") int d(); }",
                "import org.springframework.transaction.annotation.Transactional;",
                "class X { @org.springframework.transaction.annotation.Transactional void f() {} }",
                "class X { void f(java.sql.Connection c) throws Exception { c.setAutoCommit(false); } }",
                "class X { Object o = com.maccms.backend.common.write.WriteDatabase.open(null, null); }",
                "class X { Object o = com.maccms.backend.common.write.WriteRegistry.of(java.util.List.of()); }",
                "import com.zaxxer.hikari.HikariDataSource;",
                "import org.mybatis.spring.SqlSessionTemplate;",
                "class X { String k = \"maccms.write.url\"; }",
                "class X { String k = System.getenv(\"MACCMS_WRITE_DB_PASSWORD\"); }")) {
            String src = "package com.maccms.backend.member.infrastructure;\n" + b;
            assertThat(javaViolations(src)).as(b).isNotEmpty();
        }
        // WriteDatabase 只能在 infrastructure 使用；@WriteMapper 只能标在 infrastructure 下的接口上，不能同时标 @Mapper
        assertThat(javaViolations("package com.maccms.backend.member.application;\n"
                + "class S { com.maccms.backend.common.write.WriteDatabase db; }")).isNotEmpty();
        assertThat(javaViolations("package com.maccms.backend.member.application;\n"
                + "@com.maccms.backend.common.write.WriteMapper interface M {}")).isNotEmpty();
        assertThat(javaViolations("package com.maccms.backend.member.infrastructure;\n"
                + "@com.maccms.backend.common.write.WriteMapper class M {}")).isNotEmpty();
        assertThat(javaViolations("package com.maccms.backend.member.infrastructure;\n"
                + "@org.apache.ibatis.annotations.Mapper @com.maccms.backend.common.write.WriteMapper interface M {}"))
                .isNotEmpty();
        // common.write 自己可以使用这些入口
        assertThat(javaViolations("package com.maccms.backend.common.write;\n"
                + "import com.zaxxer.hikari.HikariDataSource;\nclass W { String k = \"maccms.write\"; "
                + "Object o = WriteRegistry.of(java.util.List.of()); }")).isEmpty();

        // 登记表：表不在矩阵、批次不在矩阵、写入方矩阵外的组合
        assertThat(registryViolations(List.of(new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B09")),
                false)).isEmpty();
        for (WritePermission p : List.of(
                new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B11"),
                new WritePermission("member", "mac_userx", WriteOperation.UPDATE, "B09"),
                new WritePermission("member", "mac_group", WriteOperation.DELETE, "B09"),
                new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B07"),
                new WritePermission("drill", "mac_user", WriteOperation.UPDATE, "B09"))) {
            assertThat(registryViolations(List.of(p), false)).as(p.toString()).isNotEmpty();
        }
        assertThat(registryViolations(List.of(new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B07")),
                true)).isNotEmpty();
    }

    // ---- 规则实现 ----

    /**
     * 登记表检查：表必须在写入方矩阵中，批次必须是矩阵为该表列出的批次。{@code drill=true} 时检查演练登记表：
     * 模块必须以 {@code drill} 开头、批次固定为 B07（只在一次性库上使用）；正式登记表不得出现 {@code drill} 模块。
     */
    static List<String> registryViolations(List<WritePermission> permissions, boolean drill) {
        List<String> out = new ArrayList<>();
        for (WritePermission p : permissions) {
            Set<String> batches = OWNER_MATRIX.get(p.table());
            boolean drillModule = p.module().startsWith("drill");
            if (batches == null) {
                out.add("登记了写入方矩阵之外的表：" + p.table());
            } else if (drill) {
                if (!drillModule || !DrillRegistry.BATCH.equals(p.batch())) {
                    out.add("演练登记只能是 drill* 模块、批次 " + DrillRegistry.BATCH);
                }
            } else if (drillModule) {
                out.add("正式登记表不能出现演练模块：" + p.module());
            } else if (!batches.contains(p.batch())) {
                out.add("写入方矩阵中 " + p.table() + " 只能由 " + new TreeSet<>(batches) + " 登记，不能由 " + p.batch());
            }
        }
        return out;
    }

    /** 源码中标了 @WriteMapper 的类型全限定名。 */
    static Set<String> writeMapperTypes(Iterable<String> sources) {
        Set<String> out = new LinkedHashSet<>();
        for (String src : sources) {
            String code = JavaSources.stripCommentsAndStrings(src);
            if (!WRITE_MAPPER_ANNOTATION.matcher(code).find()) {
                continue;
            }
            Matcher pkg = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(code);
            Matcher type = TYPE_DECL.matcher(code);
            if (pkg.find() && type.find()) {
                out.add(pkg.group(1) + "." + type.group(2));
            }
        }
        return out;
    }

    static List<String> javaViolations(String source) {
        List<String> out = new ArrayList<>();
        String pkg = JavaSources.modulePackage(source);
        if (pkg == null) {
            return out;
        }
        boolean commonWrite = pkg.equals("common.write");
        String code = JavaSources.stripCommentsAndStrings(source);
        String text = Pattern.compile("\"\\s*\\+\\s*\"").matcher(JavaSources.stripComments(source)).replaceAll("")
                .replaceAll("\\\\[ntrfs]", " ");

        Matcher w = WRITE_SQL_TEXT.matcher(text);
        while (w.find()) {
            out.add("写 SQL 只能写在 mapper-write XML：" + w.group(1).replaceAll("\\s+", " "));
        }
        Matcher f = FORBIDDEN_CODE.matcher(code);
        while (f.find()) {
            out.add("不允许写注解或事务（MyISAM 无事务，用 WriteSequence）：" + f.group(1));
        }
        if (!commonWrite) {
            Matcher e = WRITE_ENTRY_CODE.matcher(code);
            while (e.find()) {
                out.add("可写数据源入口只能在 common.write：" + e.group(1));
            }
            Matcher c = WRITE_CONFIG_TEXT.matcher(JavaSources.stripComments(source));
            while (c.find()) {
                out.add("可写数据源配置只能在 common.write 读取：" + c.group(1));
            }
            if (Pattern.compile("\\bWriteDatabase\\b").matcher(code).find() && !INFRASTRUCTURE_PKG.matcher(pkg).matches()) {
                out.add("只有 <module>.infrastructure 可以使用 WriteDatabase");
            }
        }
        if (WRITE_MAPPER_ANNOTATION.matcher(code).find()) {
            Matcher t = TYPE_DECL.matcher(code);
            if (!INFRASTRUCTURE_PKG.matcher(pkg).matches()) {
                out.add("@WriteMapper 只能用在 <module>.infrastructure 包下");
            }
            if (!t.find() || !"interface".equals(t.group(1))) {
                out.add("@WriteMapper 只能标在接口上");
            }
            if (MYBATIS_MAPPER_ANNOTATION.matcher(code).find()) {
                out.add("@WriteMapper 不能同时标 @Mapper（会绑定到只读连接池）");
            }
        }
        return out;
    }

    static List<String> xmlViolations(String relPath, String rawXml, WriteRegistry registry, Set<String> writeMapperTypes) {
        List<String> out = new ArrayList<>();
        String xml = JavaSources.stripXmlComments(rawXml);
        Matcher ns = NAMESPACE.matcher(xml);
        String namespace = ns.find() ? ns.group(1) : "";
        String module = null;
        try {
            module = WriteRegistry.moduleOf(namespace);
        } catch (WriteRejectedException e) {
            out.add("命名空间 " + namespace + "：" + e.getMessage());
        }
        if (module != null && !relPath.replace('\\', '/').startsWith(module + "/")) {
            out.add("写 Mapper XML 必须放在 mapper-write/" + module + "/ 下");
        }
        if (!writeMapperTypes.contains(namespace)) {
            out.add("命名空间 " + namespace + " 不是标了 @WriteMapper 的接口");
        }
        Matcher fe = FORBIDDEN_ELEMENT.matcher(xml);
        while (fe.find()) {
            out.add("写 Mapper XML 不允许 <" + fe.group(1) + ">");
        }
        if (xml.contains("${")) {
            out.add("写 Mapper XML 不允许 ${}");
        }
        Matcher st = STATEMENT.matcher(xml);
        while (st.find()) {
            Matcher idm = STATEMENT_ID.matcher(st.group(2));
            String id = idm.find() ? idm.group(1) : "?";
            String sql = TAG.matcher(st.group(3).replace("<![CDATA[", " ").replace("]]>", " ")).replaceAll(" ")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&amp;", "&").replaceAll("#\\{[^}]*}", "?");
            try {
                WriteStatement statement = WriteStatement.parse(sql);
                String element = st.group(1).toUpperCase(Locale.ROOT);
                if (!statement.operation().name().equals(element)) {
                    out.add(id + "：<" + st.group(1) + "> 中是 " + statement.operation());
                }
                if (module != null) {
                    registry.check(namespace, statement);
                }
            } catch (WriteRejectedException e) {
                out.add(id + "：" + e.getMessage());
            }
        }
        return out;
    }
}
