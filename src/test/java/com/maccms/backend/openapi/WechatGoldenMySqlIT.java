package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.openapi.application.WechatSettings;
import com.maccms.backend.openapi.application.WechatSettingsSource;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
import java.io.InputStream;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B27 微信公众号入站接口的 PHP 黄金对照（./mvnw verify -Pmysql-it；docs/architecture/29-b27-wechat.md 第 6 节）。
 *
 * <p>新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构，写入黄金样本里记录的分类与视频种子（与
 * src/test/php/wechat_golden.php 相同）→ 以真实 HTTP 启动应用（openapi 打开、视频搜索打开）→ 每个场景按黄金样本的旧配置重放
 * （{@link WechatSettingsSource}）→ 发送同样的请求：除登记差异外，状态码相同、正文与 PHP 7.3、8.0 逐字节相同（旧模板的字面换行按仓库
 * 保存的 LF 比较，D-B27-1；{@code CreateTime} 须落在新版请求前后的秒级时间窗内）；登记差异逐条断言新版行为 → 全部请求前后所有表的
 * 内容校验和不变（不写库）→ 表结构不变 → 删除该一次性库。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
class WechatGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Pattern CREATE_TIME = Pattern.compile("<CreateTime>(\\d+)</CreateTime>");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String UNKNOWN = "unknow msg type: ";

    /**
     * 登记差异（29 文档第 5 节）：场景/步骤 → 新版的状态码与正文。{@code null} 正文表示只断言状态码与空正文以外的条件在测试里单独写。
     */
    private static final Map<String, String> DIFF = Map.ofEntries(
            // D-B27-4：旧版调用不存在的方法，ThinkPHP 整页 500；新版 500、空正文
            Map.entry("message/image", ""), Map.entry("message/voice", ""), Map.entry("message/location", ""),
            Map.entry("message/video", ""),
            // D-B27-3：带 DOCTYPE 的正文按解析失败处理（旧版展开内部实体、丢弃外部实体后继续）
            Map.entry("message/internal-entity", UNKNOWN), Map.entry("message/xxe", UNKNOWN),
            Map.entry("message/xxe-param", UNKNOWN));
    /** D-B27-5：关键字按字面匹配，旧版 {@code %} 是 LIKE 通配符。新版回复"没找到资源"（与 message/search-miss 的正文相同）。 */
    private static final String LITERAL_LIKE = "message/search-percent";

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static JsonNode golden73;
    private static JsonNode golden80;
    private static final AtomicReference<WechatSettings> SETTINGS = new AtomicReference<>(WechatSettings.closed());
    private static ConfigurableApplicationContext app;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        golden73 = read("/openapi/wechat/php73-golden.json");
        golden80 = read("/openapi/wechat/php80-golden.json");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法");
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
            }
            st.execute("SET SESSION sql_mode = ''");
            st.execute(golden73.get("typesSql").asString());
            for (JsonNode row : golden73.get("seed")) {
                insert(c, "mac_vod", row);
            }
            st.execute("SET SESSION sql_mode = DEFAULT");
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.openapi.enabled=true",
                "--maccms.content.search.enabled=true"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean("goldenWechatSettings",
                        WechatSettingsSource.class, () -> SETTINGS::get))
                .run(args.toArray(String[]::new));
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
            if (dbCreated && DB_NAME.matcher(dbName).matches()) {
                Map<String, String> now;
                try (Connection c = admin(dbName)) {
                    now = SchemaFingerprint.of(c, dbName);
                }
                try (Connection c = admin(null); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE `" + dbName + "`");
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                            + "WHERE schema_name = '" + dbName + "'")) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                    }
                }
                assertThat(now).as("B02 表结构护栏：表结构与导入 install.sql 时一致").isEqualTo(schemaAtImport);
                System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            }
        }
    }

    record Reply(int status, String contentType, String csp, byte[] body, long started, long after) {
    }

    @Test
    void repliesMatchPhpGoldenAndNothingIsWritten() throws Exception {
        assertThat(golden73.get("php").asString()).startsWith("7.3.");
        assertThat(golden80.get("php").asString()).startsWith("8.0.");
        Map<String, String> before = checksums();
        String xxeMarker = golden73.get("xxeMarker").asString();
        Map<String, byte[]> missReply = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int registered = 0;
        Iterator<JsonNode> s80 = golden80.get("scenarios").iterator();
        for (JsonNode sc : golden73.get("scenarios")) {
            JsonNode sc80 = s80.next();
            assertThat(sc80.get("label").asString()).isEqualTo(sc.get("label").asString());
            SETTINGS.set(WechatSettings.from(VarExportParser.parse(Base64.getDecoder().decode(sc.get("maccmsPhp").asString()))));
            Iterator<JsonNode> st80 = sc80.get("steps").iterator();
            for (JsonNode st : sc.get("steps")) {
                JsonNode step80 = st80.next();
                String id = sc.get("label").asString() + "/" + st.get("label").asString();
                assertThat(st.get("vodUnchanged").asBoolean()).as(id + " 旧版不写库").isTrue();
                Reply r = send(st);
                assertThat(r.contentType()).as(id).isEqualTo("text/html;charset=utf-8");
                assertThat(r.csp()).as(id).isEqualTo("default-src 'none'; frame-ancestors 'none'");
                assertThat(new String(r.body(), StandardCharsets.UTF_8)).as(id + " 不泄露 XXE 文件").doesNotContain(xxeMarker);
                byte[] php73 = Base64.getDecoder().decode(st.get("response").asString());
                byte[] php80 = Base64.getDecoder().decode(step80.get("response").asString());
                assertThat(new String(php73, StandardCharsets.UTF_8)).doesNotContain(xxeMarker);
                if (id.equals("message/search-miss")) {
                    missReply.put("miss", stripTime(r.body()));
                }
                if (DIFF.containsKey(id)) {
                    registered++;
                    int expectStatus = DIFF.get(id).isEmpty() ? 500 : 200;
                    if (r.status() != expectStatus || !new String(r.body(), StandardCharsets.UTF_8).equals(DIFF.get(id))) {
                        failures.add(id + "（登记差异）: " + r.status() + " " + new String(r.body(), StandardCharsets.UTF_8));
                    }
                    if (expectStatus == 500 && st.get("status").asInt() != 500) {
                        failures.add(id + "：旧版不是 500");
                    }
                    continue;
                }
                if (id.equals(LITERAL_LIKE)) {
                    registered++;
                    if (!java.util.Arrays.equals(stripTime(r.body()), missReply.get("miss"))) {
                        failures.add(id + "（D-B27-5）: " + new String(r.body(), StandardCharsets.UTF_8));
                    }
                    continue;
                }
                compared++;
                checkTime(id, r, failures);
                for (Map.Entry<String, Object[]> g : Map.of("7.3", new Object[]{st, php73}, "8.0",
                        new Object[]{step80, php80}).entrySet()) {
                    JsonNode gs = (JsonNode) g.getValue()[0];
                    byte[] expected = stripTime(lf((byte[]) g.getValue()[1]));
                    if (r.status() != gs.get("status").asInt()) {
                        failures.add(id + " PHP " + g.getKey() + " 状态码 " + gs.get("status").asInt() + " ≠ " + r.status());
                    } else if (!java.util.Arrays.equals(stripTime(r.body()), expected)) {
                        failures.add(id + " PHP " + g.getKey() + "\n  php : " + new String(expected, StandardCharsets.UTF_8)
                                + "\n  java: " + new String(stripTime(r.body()), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        assertThat(failures).as("与 PHP 黄金不一致").isEmpty();
        assertThat(compared).as("逐字节对比的步数").isEqualTo(50);
        assertThat(registered).as("登记差异的步数").isEqualTo(8);
        assertThat(checksums()).as("全部请求前后表内容不变").isEqualTo(before);
    }

    /** 路由与正文上限（第 3 节）：只有 index 操作；正文超过 1 MiB → 413；表单正文不被参数解析吃掉（旧版读 php://input）。 */
    @Test
    void routesAndBodyLimit() throws Exception {
        SETTINGS.set(WechatSettings.from(VarExportParser.parse(Base64.getDecoder().decode(
                golden73.get("scenarios").get(1).get("maccmsPhp").asString()))));
        for (String p : new String[]{"/api.php/wechat/other", "/api.php/wechat/Index", "/api.php/wechatx"}) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + p)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(p).isEqualTo(404);
        }
        for (String p : new String[]{"/api.php/wechat/", "/api.php/wechat.html", "/api.php/wechat/index/a/b/"}) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + p)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(p).isEqualTo(200);
            assertThat(r.body()).as(p).isEqualTo("other msg");
        }
        HttpResponse<String> big = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api.php/wechat"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1024 * 1024 + 1])).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(big.statusCode()).isEqualTo(413);
        HttpResponse<String> form = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api.php/wechat?x=1"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("<xml><MsgType>link</MsgType><FromUserName>u</FromUserName></xml>"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(form.body()).contains("你发送的是链接已收到");
    }

    /** CreateTime 必须落在新版请求前后的秒级时间窗内。 */
    private static void checkTime(String id, Reply r, List<String> failures) {
        Matcher m = CREATE_TIME.matcher(new String(r.body(), StandardCharsets.UTF_8));
        if (m.find()) {
            long t = Long.parseLong(m.group(1));
            if (t < r.started() || t > r.after()) {
                failures.add(id + " CreateTime " + t + " 不在 [" + r.started() + ", " + r.after() + "]");
            }
        }
    }

    private static byte[] stripTime(byte[] b) {
        return CREATE_TIME.matcher(new String(b, StandardCharsets.UTF_8)).replaceAll("<CreateTime>T</CreateTime>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** D-B27-1：旧站检出为 CRLF 时模板的字面换行是 \r\n，仓库保存的是 LF。 */
    private static byte[] lf(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Reply send(JsonNode st) throws IOException, InterruptedException {
        String query = st.get("query").asString();
        URI uri = URI.create(baseUrl + "/api.php" + st.get("path").asString() + (query.isEmpty() ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        JsonNode body = st.get("body");
        if (body == null || body.isNull()) {
            b.method(st.get("method").asString(), HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", st.get("requestContentType").asString())
                    .method(st.get("method").asString(), HttpRequest.BodyPublishers.ofString(body.asString()));
        }
        long started = System.currentTimeMillis() / 1000;
        HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        long after = System.currentTimeMillis() / 1000;
        return new Reply(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse("").replace(" ", ""),
                resp.headers().firstValue("Content-Security-Policy").orElse(""), resp.body(), started, after);
    }

    /** 全部表的内容校验和（CHECKSUM TABLE）。 */
    private static Map<String, String> checksums() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + dbName + "' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                try (ResultSet rs = st.executeQuery("CHECKSUM TABLE `" + t + "`")) {
                    rs.next();
                    out.put(t, rs.getString(2));
                }
            }
        }
        assertThat(out).containsKey("mac_vod");
        return out;
    }

    private static void insert(Connection c, String table, JsonNode row) throws SQLException {
        List<String> cols = new ArrayList<>();
        row.propertyNames().forEach(cols::add);
        String sql = "INSERT INTO `" + table + "` (`" + String.join("`, `", cols) + "`) VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < cols.size(); i++) {
                JsonNode v = row.get(cols.get(i));
                ps.setString(i + 1, v.asString());
            }
            ps.executeUpdate();
        }
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream in = WechatGoldenMySqlIT.class.getResourceAsStream(resource)) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 README.md");
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

    private static List<String> statements(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n")) {
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
