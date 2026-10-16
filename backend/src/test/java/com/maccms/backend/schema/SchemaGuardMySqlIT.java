package com.maccms.backend.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 表结构护栏的运行期部分（B02，./mvnw verify -Pmysql-it；docs/architecture/11-guards-and-legacy-config.md 第 3.2 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 原样导入 application/install/sql/install.sql →
 * 取 information_schema 指纹（{@link SchemaFingerprint}）→ 以真实 HTTP 启动应用，打开全部只读功能（content 搜索、site 页面与搜索、
 * 旧配置目录、W2）逐项请求 → 关闭应用 → 再取指纹，必须与导入时完全一致 → 在同一一次性库里执行几条合成 DDL，证明比对能发现
 * 列、引擎、索引、表的变化 → 删除该库。
 *
 * <p>不连接、不读写任何已有库（包括 maccms_dev）。其他 *MySqlIT 也在删库前做同样的指纹比对。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaGuardMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> atImport;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            atImport = SchemaFingerprint.of(c, dbName);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        System.clearProperty("spring.datasource.password");
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
        }
    }

    @Test
    @Order(1)
    void importedSchemaIsTheLegacyOne() {
        assertThat(SchemaFingerprint.count(atImport, "table")).as("install.sql 的 26 张表").isEqualTo(26);
        assertThat(atImport.get("table mac_vod")).contains("ENGINE=MyISAM").contains("utf8");
        assertThat(atImport).containsKey("column mac_vod.vod_id").containsKey("index mac_vod.PRIMARY#1");
        assertThat(SchemaFingerprint.count(atImport, "trigger") + SchemaFingerprint.count(atImport, "routine")
                + SchemaFingerprint.count(atImport, "event") + SchemaFingerprint.count(atImport, "view")).isZero();
    }

    @Test
    @Order(2)
    void runningTheApplicationDoesNotChangeTheSchema() throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.content.search.enabled=true",
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/",
                "--maccms.legacy-config.dir=" + Path.of("..", "application").toAbsolutePath().normalize(),
                "--maccms.browser-external.w2=true"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        ConfigurableApplicationContext app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .run(args.toArray(String[]::new));
        try {
            String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            for (String path : List.of("/actuator/health", "/api/v1/video-categories", "/api/v1/videos",
                    "/api/v1/videos?categoryId=1&sort=hits&page=2", "/api/v1/videos?keyword=x&area=y&year=2024",
                    "/api/v1/videos/1", "/", "/index.php/vod/type/id/1.html", "/index.php/vod/detail/id/1.html",
                    "/index.php/vod/search.html?wd=x", "/index.php/vod/search.html?area=y")) {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(r.statusCode()).as(path).isLessThan(500);
            }
        } finally {
            app.close();
            System.clearProperty("spring.datasource.password");
        }
        try (Connection c = admin(dbName)) {
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .as("运行应用前后表结构必须与导入 install.sql 时一致").isEmpty();
        }
    }

    /** 自检：比对确实能发现结构变化。合成 DDL 只在本测试新建的一次性库里执行。 */
    @Test
    @Order(3)
    void fingerprintDetectsStructuralChanges() throws Exception {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE mac_vod ADD COLUMN zz_b02_probe INT NOT NULL DEFAULT 0");
            List<String> d = SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName));
            assertThat(d).anySatisfy(s -> assertThat(s).contains("column mac_vod.zz_b02_probe"));

            st.execute("ALTER TABLE mac_vod DROP COLUMN zz_b02_probe");
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName))).isEmpty();

            st.execute("ALTER TABLE mac_link ENGINE=InnoDB");
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .anySatisfy(s -> assertThat(s).contains("table mac_link").contains("InnoDB"));
            st.execute("ALTER TABLE mac_link ENGINE=MyISAM");

            st.execute("CREATE INDEX zz_b02_idx ON mac_vod (vod_name)");
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .anySatisfy(s -> assertThat(s).contains("index mac_vod.zz_b02_idx"));
            st.execute("DROP INDEX zz_b02_idx ON mac_vod");

            st.execute("ALTER TABLE mac_art MODIFY art_name VARCHAR(300) NOT NULL DEFAULT ''");
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .anySatisfy(s -> assertThat(s).contains("column mac_art.art_name"));

            st.execute("CREATE TABLE zz_b02_probe (id INT)");
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .anySatisfy(s -> assertThat(s).startsWith("新增 table zz_b02_probe"));
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 backend/README.md");
        }
        return v;
    }

    private static String jdbcUrlFor(String db) {
        int q = adminUrl.indexOf('?');
        String params = q >= 0 ? adminUrl.substring(q) : "";
        String path = q >= 0 ? adminUrl.substring(0, q) : adminUrl;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + (db == null ? "" : db) + (params.isEmpty() ? "?characterEncoding=UTF-8" : params);
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static List<String> installStatements() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : sb.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }
}
