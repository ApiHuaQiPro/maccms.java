package com.maccms.backend.staticgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.staticgen.application.StaticFiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * B16 端到端（./mvnw verify -Pmysql-it，25 文档第 9 节）：一次性库 {@code maccms_it_<时间>_<随机>} + 临时站点根目录，真实 HTTP 启动应用
 * （前台、可写数据源、定时任务入口打开）。
 * <ol>
 * <li>定时任务入口：本机请求执行 {@code make} 任务，静态文件写到旧路径，内容与浏览器访问动态地址逐字节相同；{@code vod_time_make} 等
 * 只有这一列被改（其余列逐列不变）；{@code timming.php} 的 runtime 回写；</li>
 * <li>未通过鉴权（本机对端 + 转发头、未登记可信代理）的请求含 {@code enforce=1}：403，不执行、{@code timming.php} 与库都不变；</li>
 * <li>回滚：{@link StaticFiles#cleanup()} 删除本批生成的全部文件，站点根目录回到生成前；表结构与导入时一致。</li>
 * </ol>
 * 不连接、不读写任何已有库（包括 maccms_dev）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaticTimmingMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path REPO_EXTRA = Path.of("..", "application", "extra");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** 2026-10-26 08:00:00 PRC 之后（"今天"的内容）。 */
    private static final long T1 = 1792972800L;

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;
    private static Path root;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            seed(st);
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        root = Files.createTempDirectory("maccms-b16-it");
        prepareSite(root);

        String url = jdbcUrlFor(dbName);
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + adminUser,
                "--maccms.write.enabled=true", "--maccms.write.url=" + url, "--maccms.write.username=" + adminUser,
                "--maccms.site.enabled=true", "--maccms.site.name=B16 合成站", "--maccms.site.base-url=http://127.0.0.1/",
                "--maccms.content.enforce-guest-popedom=false",
                "--maccms.legacy-config.dir=" + root.resolve("application"),
                "--maccms.timming.enabled=true",
                "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class).run(args.toArray(String[]::new));
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
            if (dbCreated && DB_NAME.matcher(dbName).matches()) {
                Map<String, String> now;
                try (Connection c = admin(dbName)) {
                    now = SchemaFingerprint.of(c, dbName);
                }
                try (Connection c = admin(null); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE `" + dbName + "`");
                }
                if (schemaAtImport != null) {
                    assertThat(SchemaFingerprint.diff(schemaAtImport, now)).as("表结构与导入 install.sql 时一致").isEmpty();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 数据与站点目录

    private static void seed(Statement st) throws SQLException {
        // 合成数据只给出用到的列，其余 TEXT 列在严格模式下没有默认值：只对这个建数据的会话放宽
        st.execute("SET SESSION sql_mode = ''");
        st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_tpl,"
                + "type_tpl_list,type_tpl_detail,type_extend) VALUES (1,'电影','dianying',1,1,0,1,'','','',''),"
                + "(6,'动作片','dongzuo',2,1,1,1,'','','',''),(21,'资讯','zixun',3,2,0,1,'','','','')");
        st.execute("INSERT INTO mac_vod (vod_id,type_id,type_id_1,vod_name,vod_en,vod_status,vod_time,vod_time_add,"
                + "vod_time_make,vod_content,vod_play_from,vod_play_url) VALUES "
                + "(1,6,1,'合成视频一','hecheng1',1," + T1 + "," + T1 + ",0,'内容一','',''),"
                + "(2,6,1,'合成视频二','hecheng2',1," + (T1 - 86400 * 30) + "," + (T1 - 86400 * 30) + ",0,'内容二','','')");
        st.execute("INSERT INTO mac_art (art_id,type_id,type_id_1,art_name,art_en,art_status,art_time,art_time_add,"
                + "art_time_make,art_title,art_note,art_content) VALUES "
                + "(55,21,0,'合成文章','wenzhang55',1," + T1 + "," + T1 + ",0,'第一页$$$第二页','','正文一$$$正文二')");
    }

    private static void prepareSite(Path r) throws IOException {
        Path extra = Files.createDirectories(r.resolve("application/extra"));
        try (Stream<Path> s = Files.list(REPO_EXTRA)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".php")).toList()) {
                Files.copy(p, extra.resolve(p.getFileName().toString()));
            }
        }
        Files.writeString(r.resolve("index.php"), "<?php // legacy entry");
        PhpArray maccms = VarExportParser.parse(Files.readAllBytes(extra.resolve("maccms.php")));
        Map<Object, PhpValue> all = new LinkedHashMap<>(maccms.entries());
        Map<Object, PhpValue> view = new LinkedHashMap<>(((PhpArray) maccms.get("view")).entries());
        for (String k : List.of("index", "vod_type", "vod_detail", "art_detail", "topic_index", "topic_detail", "art_type")) {
            view.put(k, new PhpValue.PhpString("2"));
        }
        all.put("view", new PhpArray(view));
        Files.write(extra.resolve("maccms.php"), VarExportWriter.macArr2File(new PhpArray(all)));
        Files.writeString(extra.resolve("timming.php"), timming(), StandardCharsets.UTF_8);
    }

    private static String timming() {
        StringBuilder sb = new StringBuilder("<?php\nreturn array (\n");
        String[][] tasks = {{"vodinfo", "ac=info&tab=vod&ids=1,2"}, {"artinfo", "ac=info&tab=art&ids=55"},
                {"vodtype", "ac=type&tab=vod&vodtype=1,6"}, {"home", "ac=index"}};
        for (String[] t : tasks) {
            sb.append("  '").append(t[0]).append("' => \n  array (\n    'id' => '").append(t[0])
                    .append("',\n    'status' => '1',\n    'name' => '").append(t[0]).append("',\n    'des' => '',\n")
                    .append("    'file' => 'make',\n    'param' => '").append(t[1]).append("',\n")
                    .append("    'weeks' => '1,2,3,4,5,6,0',\n    'hours' => '00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,")
                    .append("17,18,19,20,21,22,23',\n    'runtime' => 1,\n  ),\n");
        }
        return sb.append(");").toString();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @Order(1)
    void unauthenticatedRequestsChangeNothing() throws Exception {
        String before = sha(root.resolve("application/extra/timming.php"));
        Map<Long, String> rows = vodRows();
        HttpResponse<String> r = get("/api.php/timming/index?enforce=1&name=vodinfo", Map.of("X-Forwarded-For", "203.0.113.9"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(sha(root.resolve("application/extra/timming.php"))).isEqualTo(before);
        assertThat(vodRows()).isEqualTo(rows);
        assertThat(root.resolve("vodhtml")).doesNotExist();
    }

    @Test
    @Order(2)
    void localTriggerGeneratesFilesEqualToDynamicPagesAndMarksMade() throws Exception {
        Map<Long, String> before = vodRows();
        long start = System.currentTimeMillis() / 1000;
        HttpResponse<String> r = get("/api.php/timming/index?enforce=1&name=vodinfo", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("任务：vodinfo，状态：开启").contains("---执行<br>").doesNotContain("生成失败");
        for (long id : List.of(1L, 2L)) {
            String file = Files.readString(root.resolve("vodhtml/" + id + "/index.html"), StandardCharsets.UTF_8);
            HttpResponse<String> page = get("/index.php/vod/detail/id/" + id + ".html", Map.of());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(file).as("vod " + id).isEqualTo(page.body());
        }
        // 只有 vod_time_make 变了
        Map<Long, String> after = vodRows();
        for (long id : List.of(1L, 2L)) {
            assertThat(stripTimeMake(after.get(id))).isEqualTo(stripTimeMake(before.get(id)));
            assertThat(timeMake("mac_vod", "vod", id)).isBetween(start, start + 120);
        }
        assertThat(Files.readString(root.resolve("application/extra/timming.php"), StandardCharsets.UTF_8))
                .doesNotContain("'runtime' => 1,\n  ),\n  'artinfo'");

        HttpResponse<String> art = get("/api.php/timming/index?enforce=1&name=artinfo", Map.of());
        assertThat(art.body()).doesNotContain("生成失败");
        assertThat(Files.readString(root.resolve("arthtml/55/index.html"), StandardCharsets.UTF_8))
                .isEqualTo(get("/index.php/art/detail/id/55.html", Map.of()).body());
        assertThat(Files.readString(root.resolve("arthtml/55/index_2.html"), StandardCharsets.UTF_8))
                .isEqualTo(get("/index.php/art/detail/id/55/page/2.html", Map.of()).body());
        assertThat(timeMake("mac_art", "art", 55)).isBetween(start, start + 120);

        HttpResponse<String> type = get("/api.php/timming/index?enforce=1&name=vodtype", Map.of());
        assertThat(type.body()).contains("定时任务完毕").doesNotContain("生成失败");
        assertThat(Files.readString(root.resolve("vodtypehtml/6/index.html"), StandardCharsets.UTF_8))
                .isEqualTo(get("/index.php/vod/type/id/6.html", Map.of()).body());

        HttpResponse<String> home = get("/api.php/timming/index?enforce=1&name=home", Map.of());
        assertThat(home.body()).doesNotContain("生成失败");
        assertThat(Files.readString(root.resolve("index.html"), StandardCharsets.UTF_8)).isEqualTo(get("/", Map.of()).body());
    }

    @Test
    @Order(3)
    void cleanupRemovesEverythingThisBatchWrote() throws Exception {
        StaticFiles files = app.getBean(StaticFiles.class);
        assertThat(files.generated()).contains("vodhtml/1/index.html", "arthtml/55/index_2.html", "index.html");
        StaticFiles.Cleanup c = files.cleanup();
        assertThat(c.refused()).isEmpty();
        assertThat(c.deleted()).isGreaterThanOrEqualTo(6);
        try (Stream<Path> s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith("application/extra/"))).containsExactly("index.php");
        }
    }

    // ------------------------------------------------------------------ 工具

    private static HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Map<Long, String> vodRows() throws SQLException {
        Map<Long, String> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM mac_vod ORDER BY vod_id")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getString(i)).append('\u0001');
                }
                out.put(rs.getLong("vod_id"), sb.toString());
            }
        }
        return out;
    }

    private static String stripTimeMake(String row) {
        return row.replaceAll("vod_time_make=[0-9]*\u0001", "");
    }

    private static long timeMake(String table, String tab, long id) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + tab + "_time_make FROM " + table + " WHERE " + tab + "_id=" + id)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 backend/README.md");
        }
        return v;
    }

    private static String jdbcUrlFor(String db) {
        String base = adminUrl;
        int q = base.indexOf('?');
        String params = q >= 0 ? base.substring(q) : "";
        String path = q >= 0 ? base.substring(0, q) : base;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + (db == null ? "" : db) + (params.isEmpty() ? "?characterEncoding=UTF-8" : params);
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static List<String> installStatements() throws IOException {
        String text = Files.readString(INSTALL_SQL, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
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
