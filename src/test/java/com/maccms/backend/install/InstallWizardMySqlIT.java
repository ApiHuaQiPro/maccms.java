package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.install.application.InstallSchema;
import com.maccms.backend.install.application.InstallSqlFile;
import com.maccms.backend.install.infrastructure.InstallConnections;
import com.maccms.backend.install.infrastructure.InstallDatabaseProbe;
import com.maccms.backend.install.infrastructure.InstallTarget;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * 安装向导的真实 MySQL 验证（{@code ./mvnw verify -Pmysql-it}；B22 31 文档第 9 节，APPK-2 S3-20 技术设计 §8.2 V-25、§6.14 INS-1～INS-4）。
 * 只用外部管理账号新建的一次性库 {@code maccms_it_<时间>_<随机>}（每个场景一个），结束时全部删除并确认不存在；不碰 {@code maccms_dev}。
 * <ol>
 * <li>表结构基准：jar 内 {@code install/schema-fingerprint.txt} 与在新库里执行 install.sql 的指纹完全相同，且与测试侧
 * {@code SchemaFingerprint} 读到的一致（库默认字符集一项除外）；</li>
 * <li>复放 PHP 7.3 与 8.0 的全部黄金场景（页面、校验、测试连接、可选的初始数据两个分支、安装锁），目标为<b>空库</b>：每一步的响应与旧版
 * 一致；第 5 步执行 install.sql 建出 26 张表，结构指纹与 {@link InstallSchema#expected()} 完全相同，{@code mac_group} 3 行默认会员组；
 * {@code mac_type}、{@code mac_admin} 的全部行与旧版逐行逐列相同，{@code extra/maccms.php} 除随机的 {@code interface.pass} 外逐字节相同，
 * 安装锁的内容是同样格式的时间；</li>
 * <li>新版才有的分支：库不存在、前缀不是 mac_、root 未放行、表单令牌不对、安装目录不是 /；第 5 步 INS-2 ②（已有 {@code mac_} 表 →
 * “已存在数据表”）与 ③（install.sql 改 1 字节、换成 CRLF → “文件哈希不一致”）：不执行任何 DDL、库里没有新表、不写安装锁；</li>
 * <li>向导写下的部署配置文件能让正常模式启动（只读连接池与可写数据源都连得上，{@code /actuator/health} 为 UP），启动前后表结构不变；</li>
 * <li>驱动收到的每一条语句都记录下来（Connector/J {@code profileSQL}）：DDL 只能是 install.sql 切出的语句（55 条全部出现，没有
 * {@code CREATE DATABASE}），其余只有 SELECT、INSERT 与驱动自己的会话设置。</li>
 * </ol>
 */
class InstallWizardMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path FINGERPRINT_RESOURCE = Path.of("src", "main", "resources", "install", "schema-fingerprint.txt");
    private static final Pattern DDL = Pattern.compile("(?i)^\\s*(CREATE|ALTER|DROP|RENAME|TRUNCATE|GRANT|REVOKE|OPTIMIZE|REPAIR"
            + "|ANALYZE|LOAD|REPLACE|UPDATE|DELETE)\\b");
    private static final Pattern PASS = Pattern.compile("('pass' => ')([02-9A-Z]{16})(')");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String host;
    private static String port;
    private static final List<String> created = new ArrayList<>();
    private static final Map<String, Map<String, String>> schemaAtImport = new LinkedHashMap<>();
    /** 驱动记下的语句；Spring Boot 每次启动会重置 Logback，所以每次启动应用后重新挂上（{@link #recordSql}）。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> SQL = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 给向导的连接加上驱动自带的语句记录（profileSQL 经 SLF4J 输出到 "MySQL" 记录器）。 */
    static final class RecordingConnections extends InstallConnections {
        @Override
        protected String parameters() {
            // maxQuerySizeToLog：install.sql 的 CREATE TABLE 超过驱动默认的 2048 字符，记全文才能与切分结果逐条比对
            return super.parameters() + "&profileSQL=true&logger=Slf4JLogger&maxQuerySizeToLog=1048576";
        }
    }

    @BeforeAll
    static void setUp() {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        Matcher m = Pattern.compile("^jdbc:mysql://([^:/?]+)(?::([0-9]+))?").matcher(adminUrl);
        if (!m.find()) {
            throw new IllegalStateException("MACCMS_IT_ADMIN_URL 不是 jdbc:mysql://host[:port]/ 形式");
        }
        host = m.group(1);
        port = m.group(2) == null ? "3306" : m.group(2);
    }

    static void recordSql() {
        Logger mysql = (Logger) LoggerFactory.getLogger("MySQL");
        if (mysql.getAppender("b22-sql") == null) {
            ch.qos.logback.core.AppenderBase<ILoggingEvent> a = new ch.qos.logback.core.AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent e) {
                    SQL.add(e.getFormattedMessage());
                }
            };
            a.setName("b22-sql");
            a.setContext(mysql.getLoggerContext());
            a.start();
            mysql.addAppender(a);
        }
        mysql.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    private static ConfigurableApplicationContext startRecorded(InstallGolden.Site site, boolean allowRoot, String entry) {
        ConfigurableApplicationContext app = InstallGolden.start(site, allowRoot, entry, new RecordingConnections());
        recordSql();
        return app;
    }

    @AfterAll
    static void tearDown() throws Exception {
        List<String> drift = new ArrayList<>();
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            for (String db : created) {
                if (!DB_NAME.matcher(db).matches()) {
                    continue;
                }
                Map<String, String> before = schemaAtImport.get(db);
                if (before != null) {
                    try (Connection d = admin(db)) {
                        SchemaFingerprint.diff(before, SchemaFingerprint.of(d, db)).forEach(x -> drift.add(db + ": " + x));
                    }
                }
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '"
                        + db + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除 " + db).isZero();
                }
            }
        }
        System.out.println("[mysql-it] dropped " + created.size() + " disposable databases (verified absent)");
        assertThat(drift).as("表结构与导入 install.sql 时一致").isEmpty();
    }

    // ------------------------------------------------------------------ ① 表结构基准

    @Test
    void schemaFingerprintResourceMatchesFreshInstallSql() throws Exception {
        String db = newDatabase(true);
        Map<String, String> fp = inspect(db).fingerprint();
        String actual = InstallSchema.format(fp);
        String resource = Files.exists(FINGERPRINT_RESOURCE) ? Files.readString(FINGERPRINT_RESOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
        if (!actual.equals(resource)) {
            Path out = Path.of("target", "install-schema-fingerprint.actual.txt");
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            throw new AssertionError("install/schema-fingerprint.txt 与新执行的 install.sql 不同，实际结果写在 " + out);
        }
        assertThat(InstallSchema.differences(fp)).isEmpty();
        assertThat(fp.keySet().stream().filter(k -> k.startsWith("table ")).count()).isEqualTo(26);
        // 与测试侧 SchemaFingerprint 相同（去掉库的默认字符集一项）
        Map<String, String> test = new LinkedHashMap<>(schemaAtImport.get(db));
        test.keySet().removeIf(k -> k.startsWith("schema "));
        assertThat(fp).isEqualTo(test);
    }

    // ------------------------------------------------------------------ ② 黄金复放

    @Test
    void goldenScenariosMatchPhp73And80() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        int installs = 0;
        SQL.clear();
        for (String resource : List.of(InstallGolden.PHP73, InstallGolden.PHP80)) {
            JsonNode g = InstallGolden.load(resource);
            for (JsonNode sc : g.get("scenarios")) {
                String label = sc.get("label").asString();
                // INS-1：空库，由第 5 步执行 install.sql 建表
                String db = newDatabase(false);
                InstallGolden.Site site = InstallGolden.Site.create();
                try (ConfigurableApplicationContext app = startRecorded(site, true, null)) {
                    MockMvc mvc = InstallGolden.mvc(app);
                    MockHttpSession session = new MockHttpSession();
                    for (JsonNode st : sc.get("steps")) {
                        MockHttpServletResponse r = InstallGolden.send(mvc, session, st.get("request"), vars(db));
                        String p = InstallGolden.compare(st, r);
                        compared++;
                        if (p != null) {
                            problems.add(resource + " " + label + "/" + st.get("label").asString() + "：" + p);
                        }
                    }
                    if (sc.has("state")) {
                        installs++;
                        problems.addAll(compareState(resource + " " + label, sc.get("state"), db, site));
                        problems.addAll(compareSchema(resource + " " + label, db));
                        schemaAtImport.put(db, fingerprint(db));
                        if (!Files.exists(site.deployConfig())) {
                            problems.add(resource + " " + label + "：没有写部署配置文件");
                            continue;
                        }
                        Map<String, String> deploy = readProperties(site.deployConfig());
                        assertThat(deploy.get("MACCMS_DB_URL")).isEqualTo("jdbc:mysql://" + host + ":" + port + "/" + db
                                + "?characterEncoding=UTF-8");
                        assertThat(deploy.get("MACCMS_WRITE_DB_URL")).isEqualTo(deploy.get("MACCMS_DB_URL"));
                        assertThat(deploy.get("MACCMS_WRITE_ENABLED")).isEqualTo("true");
                        assertThat(deploy.get("MACCMS_DB_USERNAME")).isEqualTo(adminUser);
                        assertThat(deploy.get("MACCMS_DB_PASSWORD")).isEqualTo(adminPassword);
                        if (resource.equals(InstallGolden.PHP80) && label.equals("install-initdata")) {
                            normalModeStartsWith(site, db);
                        }
                    } else {
                        assertThat(Files.exists(site.lock())).as(label + " 没有安装锁").isFalse();
                        assertThat(tableCount(db)).as(label + " 没有建表").isZero();
                    }
                } finally {
                    site.delete();
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(installs).isEqualTo(4);
        System.out.println("[mysql-it] B22 golden: " + compared + " steps compared against PHP 7.3/8.0");
        assertInstallStatements(List.copyOf(SQL), true);
    }

    /** 安装后的库与文件和旧版一致。 */
    private static List<String> compareState(String tag, JsonNode state, String db, InstallGolden.Site site) throws Exception {
        List<String> out = new ArrayList<>();
        for (String table : List.of("mac_type", "mac_admin")) {
            List<Map<String, String>> want = new ArrayList<>();
            for (JsonNode row : state.get(table)) {
                Map<String, String> m = new LinkedHashMap<>();
                row.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
                want.add(m);
            }
            List<Map<String, String>> got = rows(db, table);
            if (!want.equals(got)) {
                out.add(tag + " " + table + "：旧版 " + want.size() + " 行，新版 " + got.size() + " 行，内容不同");
            }
        }
        String phpConfig = new String(Base64.getDecoder().decode(state.get("maccms").asString()), StandardCharsets.UTF_8);
        String javaConfig = Files.readString(site.application().resolve("extra/maccms.php"), StandardCharsets.UTF_8);
        Matcher a = PASS.matcher(phpConfig);
        Matcher b = PASS.matcher(javaConfig);
        if (!a.find() || !b.find()) {
            out.add(tag + " maccms.php：interface.pass 不是 16 位随机串");
        } else if (!a.replaceAll("$1{rnd}$3").equals(b.replaceAll("$1{rnd}$3"))) {
            out.add(tag + " maccms.php：除 interface.pass 外内容不同");
        }
        String lock = Files.exists(site.lock()) ? Files.readString(site.lock(), StandardCharsets.UTF_8) : null;
        Pattern time = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
        if (lock == null || !time.matcher(lock).matches() || !time.matcher(state.get("lock").asString()).matches()) {
            out.add(tag + " install.lock：" + lock);
        }
        return out;
    }

    /** 第 5 步建出的表结构与 {@link InstallSchema#expected()} 完全相同（V-25），各表行数符合 install.sql 与安装写入。 */
    private static List<String> compareSchema(String tag, String db) throws Exception {
        List<String> out = new ArrayList<>();
        InstallDatabaseProbe.TargetState state = inspect(db);
        InstallSchema.differences(state.fingerprint()).forEach(d -> out.add(tag + " 表结构：" + d));
        long tables = state.fingerprint().keySet().stream().filter(k -> k.startsWith("table ")).count();
        if (tables != InstallSchema.TABLES) {
            out.add(tag + " 表数 " + tables);
        }
        InstallSchema.SEED_ROWS.forEach((t, n) -> {
            if (!n.equals(state.rows().get(t))) {
                out.add(tag + " " + t + " 行数 " + state.rows().get(t) + " ≠ " + n);
            }
        });
        if (!Long.valueOf(1L).equals(state.rows().get("mac_admin"))) {
            out.add(tag + " mac_admin 行数 " + state.rows().get("mac_admin"));
        }
        return out;
    }

    /** 正常模式按向导写下的部署配置文件启动（只读连接池与可写数据源都连上目标库），启动前后表结构不变（正常模式不执行 DDL）。 */
    private static void normalModeStartsWith(InstallGolden.Site site, String db) throws Exception {
        Map<String, String> before = fingerprint(db);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("MACCMS_SERVER_PORT=0", "MACCMS_DEPLOY_CONFIG=" + site.deployConfig(),
                        "MACCMS_LEGACY_CONFIG_DIR=" + site.application())
                .run()) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
            String health = mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andReturn().getResponse()
                    .getContentAsString();
            assertThat(health).contains("\"UP\"");
            assertThat(app.getBean(com.maccms.backend.common.write.WriteDatabase.class)).isNotNull();
        }
        assertThat(SchemaFingerprint.diff(before, fingerprint(db))).as("正常模式启动前后表结构不变").isEmpty();
    }

    // ------------------------------------------------------------------ ③ 新版才有的分支

    @Test
    void refusesWithoutDdlAndInstallsAnEmptyDatabase() throws Exception {
        SQL.clear();
        InstallGolden.Site site = InstallGolden.Site.create();
        try (ConfigurableApplicationContext app = startRecorded(site, true, "/_admin.php")) {
            MockMvc mvc = InstallGolden.mvc(app);
            MockHttpSession s = new MockHttpSession();
            String token = token(mvc, s);

            // 库不存在：不建库
            String missing = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_00000000";
            assertThat(step4(mvc, s, missing, "mac_", token)).contains("数据库不存在");
            assertThat(databaseExists(missing)).isFalse();
            // 前缀不是 mac_
            String ok = newDatabase(false);
            assertThat(step4(mvc, s, ok, "abc_", token)).contains("只支持数据库前缀 mac_");
            // 表单令牌不对
            assertThat(step4(mvc, s, ok, "mac_", "0".repeat(32))).isEqualTo("{\"code\":0,\"msg\":\"非法访问\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
            assertThat(Files.exists(site.deployConfig())).as("以上都不写部署配置").isFalse();

            // INS-2 ②：库里已有 mac_ 表（库外导入过 install.sql）→ 第 4 步通过（INS-3 不再核对结构），第 5 步“已存在数据表”，不执行 DDL
            String used = newDatabase(true);
            assertThat(step4(mvc, s, used, "mac_", token)).contains("\"code\":1");
            int mark = SQL.size();
            assertThat(step5(mvc, s, "/", token(mvc, s))).contains("\"code\":0", "已存在数据表");
            assertThat(ddlSince(mark)).as("已存在数据表：不执行 DDL").isEmpty();
            assertThat(rows(used, "mac_admin")).isEmpty();
            assertThat(Files.exists(site.lock())).isFalse();

            // 空库：第 4 步通过；安装目录不是 / 拒绝
            assertThat(step4(mvc, s, ok, "mac_", token(mvc, s))).contains("\"code\":1");
            assertThat(tableCount(ok)).as("第 4 步不建表").isZero();
            assertThat(step5(mvc, s, "/sub/", token(mvc, s))).contains("只支持安装在网站根目录");

            // INS-2 ③：临时站点的 install.sql 改 1 字节、换成 CRLF（152e5424…）→“文件哈希不一致”，不执行 DDL、库里没有 mac_ 表、不写安装锁
            Path sql = site.application().resolve("install/sql/install.sql");
            byte[] original = Files.readAllBytes(sql);
            byte[] changed = original.clone();
            changed[100] ^= 1;
            byte[] crlf = new String(original, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
            assertThat(InstallSqlFile.sha256(crlf)).startsWith("152e5424");
            for (byte[] content : List.of(changed, crlf)) {
                Files.write(sql, content);
                mark = SQL.size();
                assertThat(step5(mvc, s, "/", token(mvc, s))).contains("\"code\":0", "文件哈希不一致");
                assertThat(ddlSince(mark)).as("哈希不一致：不执行 DDL").isEmpty();
                assertThat(tableCount(ok)).as("哈希不一致：没有 mac_ 表").isZero();
                assertThat(Files.exists(site.lock())).isFalse();
            }

            // 原样的 install.sql：建表、写入初始数据与管理员，跳到 MACCMS_ADMIN_ENTRY
            Files.write(sql, original);
            assertThat(step5(mvc, s, "/", token(mvc, s))).isEqualTo("{\"code\":1,\"msg\":\"系统安装成功，欢迎您使用苹果CMS建站\",\"data\":\"\","
                    + "\"url\":\"\\/_admin.php\",\"wait\":3}");
            assertThat(compareSchema("refuses", ok)).isEmpty();
            assertThat(rows(ok, "mac_type")).hasSize(18);
            assertThat(rows(ok, "mac_admin")).hasSize(1);
            assertThat(Files.exists(site.lock())).isTrue();
            schemaAtImport.put(ok, fingerprint(ok));
        } finally {
            site.delete();
        }
        // root 未放行（只在管理账号就是 root 时能验证）
        if (adminUser.equalsIgnoreCase("root")) {
            InstallGolden.Site strict = InstallGolden.Site.create();
            try (ConfigurableApplicationContext app = startRecorded(strict, false, null)) {
                MockMvc mvc = InstallGolden.mvc(app);
                MockHttpSession s = new MockHttpSession();
                assertThat(step4(mvc, s, newDatabase(false), "mac_", token(mvc, s))).contains("MACCMS_INSTALL_ALLOW_ROOT");
            } finally {
                strict.delete();
            }
        }
        assertInstallStatements(List.copyOf(SQL), true);
    }

    /** 打开第 3 步取得新的一次性表单令牌（APPK-2 INS-SEC-4：第 5 步令牌一经校验即作废）。 */
    private static String token(MockMvc mvc, MockHttpSession s) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/install.php?step=3").session(s));
        return (String) s.getAttribute("install.token");
    }

    /** 第 {@code mark} 条记录之后驱动收到的 DDL。 */
    private static List<String> ddlSince(int mark) {
        List<String> all = List.copyOf(SQL);
        return statementsOf(all.subList(mark, all.size())).stream().filter(st -> DDL.matcher(st).find()).toList();
    }

    private static String step4(MockMvc mvc, MockHttpSession s, String db, String prefix, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/install.php?step=4").session(s)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(Map.of("hostname", host, "hostport", port, "database", db, "username", adminUser,
                                "password", adminPassword, "prefix", prefix, "cover", "0", "__token__", token))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String step5(MockMvc mvc, MockHttpSession s, String dir, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/install.php?step=5").session(s)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(Map.of("install_dir", dir, "account", "admin", "password", "Test-only-1",
                                "initdata", "1", "__token__", token))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ⑤ 语句

    /** profileSQL 事件里的语句（去重，空白规范化）。 */
    private static Set<String> statementsOf(List<String> events) {
        Set<String> statements = new LinkedHashSet<>();
        for (String msg : events) {
            int i = indexOfStatement(msg);
            if (i >= 0) {
                // 去掉驱动附在末尾的 " [Created on: …, at 调用位置]"
                int suffix = msg.lastIndexOf(" [Created on: ");
                statements.add(normalize(suffix > i ? msg.substring(i, suffix) : msg.substring(i)));
            }
        }
        return statements;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    /**
     * profileSQL 记下的每条语句（INS-2 ④、V-25）：DDL 与 install.sql 里的 INSERT 只能是 install.sql 切出的语句（{@code installed} 时 55 条
     * 全部出现）；应用自己的其余语句只有 SELECT（版本、information_schema）与 INSERT（mac_type、mac_admin）；再有就是驱动建连时的会话设置
     * （SET/SHOW/SELECT @@…）。没有 {@code CREATE DATABASE}、UPDATE、DELETE 等。
     */
    private static void assertInstallStatements(List<String> events, boolean installed) throws IOException {
        Set<String> statements = statementsOf(events);
        assertThat(statements).as("驱动记录到语句").isNotEmpty();
        Set<String> fromFile = new LinkedHashSet<>();
        installStatements().forEach(st -> fromFile.add(normalize(st)));
        assertThat(fromFile).hasSize(55);
        List<String> bad = new ArrayList<>();
        Set<String> kinds = new TreeSet<>();
        for (String st : statements) {
            String head = st.split(" ", 2)[0].toUpperCase(Locale.ROOT);
            kinds.add(head);
            if (fromFile.contains(st)) {
                continue;
            }
            if (DDL.matcher(st).find() || !Set.of("SELECT", "INSERT", "SHOW", "SET").contains(head)) {
                bad.add(st);
            }
            if (head.equals("INSERT") && !st.matches("(?is)INSERT INTO mac_(type|admin) \\(.*")) {
                bad.add(st);
            }
            if (head.equals("SET") && !st.matches("(?i)SET (autocommit|NAMES|character_set_results|SESSION TRANSACTION|"
                    + "sql_mode|net_write_timeout|SQL_SELECT_LIMIT)\\b.*")) {
                bad.add(st);
            }
        }
        System.out.println("[mysql-it] statement kinds seen by the driver: " + kinds + " (" + statements.size() + " distinct)");
        try {
            Files.write(Path.of("target", "install-sql-statements.txt"), statements, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(bad).as("只有 install.sql 的语句、SELECT/INSERT 与驱动的会话设置").isEmpty();
        assertThat(statements).noneMatch(st -> st.toUpperCase(Locale.ROOT).startsWith("CREATE DATABASE"));
        if (installed) {
            assertThat(fromFile).as("install.sql 的 55 条语句全部执行").allMatch(statements::contains);
            assertThat(statements).anyMatch(st -> st.startsWith("INSERT INTO mac_admin"));
        }
    }

    /** profileSQL 事件的文字里语句开始的位置（事件格式随驱动版本不同，取第一个语句关键字）。 */
    private static int indexOfStatement(String msg) {
        Matcher m = Pattern.compile("(?i)\\b(SELECT|INSERT|SHOW|SET|CREATE|ALTER|DROP|RENAME|TRUNCATE|UPDATE|DELETE|REPLACE"
                + "|GRANT|REVOKE|LOAD|OPTIMIZE|REPAIR|ANALYZE|USE)\\s").matcher(msg);
        return m.find() ? m.start() : -1;
    }

    // ------------------------------------------------------------------ 工具

    private static Map<String, String> vars(String db) {
        return Map.of("{host}", host, "{port}", port, "{db}", db, "{user}", adminUser, "{password}", adminPassword);
    }

    private static InstallTarget target(String db) {
        return new InstallTarget(host, Integer.parseInt(port), db, adminUser, adminPassword);
    }

    private static String newDatabase(boolean importSchema) throws Exception {
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        String db = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + db);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + db + "` DEFAULT CHARACTER SET utf8");
            created.add(db);
        }
        System.out.println("[mysql-it] created disposable database " + db);
        if (importSchema) {
            try (Connection c = admin(db); Statement st = c.createStatement()) {
                for (String sql : installStatements()) {
                    st.execute(sql);
                }
            }
        }
        schemaAtImport.put(db, fingerprint(db));
        return db;
    }

    private static Map<String, String> fingerprint(String db) throws SQLException {
        try (Connection c = admin(db)) {
            return SchemaFingerprint.of(c, db);
        }
    }

    private static boolean databaseExists(String db) throws SQLException {
        try (Connection c = admin(null); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '" + db + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static int tableCount(String db) throws SQLException {
        try (Connection c = admin(null); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + db
                     + "' AND table_name LIKE 'mac\\_%'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<Map<String, String>> rows(String db, String table) throws SQLException {
        String key = table.equals("mac_type") ? "type_id" : table.equals("mac_admin") ? "admin_id" : "gbook_id";
        List<Map<String, String>> out = new ArrayList<>();
        try (Connection c = admin(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY " + key)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i), rs.getString(i));
                }
                out.add(m);
            }
        }
        return out;
    }

    private static Map<String, String> readProperties(Path p) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(p)) {
            props.load(in);
        }
        Map<String, String> out = new LinkedHashMap<>();
        props.stringPropertyNames().forEach(k -> out.put(k, props.getProperty(k)));
        return out;
    }

    private static String form(Map<String, String> fields) {
        List<String> pairs = new ArrayList<>();
        fields.forEach((k, v) -> pairs.add(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    /** 与第 5 步相同的切分（{@link InstallSqlFile#statements}，PHP {@code mac_parse_sql}）。 */
    private static List<String> installStatements() throws IOException {
        byte[] bytes = Files.readAllBytes(INSTALL_SQL);
        assertThat(InstallSqlFile.matches(bytes)).as("仓库 install.sql 与 INSTALL_SQL_SHA256 一致（LF 检出）").isTrue();
        return InstallSqlFile.statements(bytes);
    }

    private static InstallDatabaseProbe.TargetState inspect(String db) {
        return new InstallDatabaseProbe(new InstallConnections()).inspect(target(db));
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(db == null ? adminUrl : jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static String jdbcUrlFor(String db) {
        int q = adminUrl.indexOf('?');
        String base = q < 0 ? adminUrl : adminUrl.substring(0, q);
        String params = q < 0 ? "" : adminUrl.substring(q);
        if (!base.endsWith("/")) {
            base = base.substring(0, base.lastIndexOf('/') + 1);
        }
        return base + db + params;
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "（见 README.md）");
        }
        return v;
    }
}
