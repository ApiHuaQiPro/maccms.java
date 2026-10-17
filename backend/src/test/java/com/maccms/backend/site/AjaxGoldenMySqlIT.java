package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.ByteArrayInputStream;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * B06 旧 index 模块的 ajax/data、ajax/suggest、ajax/desktop 与 qrcode 的 PHP 黄金对照（./mvnw verify -Pmysql-it；
 * docs/architecture/16-b06-misc-pages-provide.md 第 4.7–4.10、7 节）。
 *
 * <p>新建一次性库 → 导入 install.sql 与 openapi/provide/seed.sql（与 provide_golden.php 相同）→ 以真实 HTTP 启动应用（site 打开，
 * 站名/站点地址与黄金样本相同，旧配置目录为仓库 application/，搜索页与包含匹配开关打开）→ 逐条请求黄金样本中 index 入口的用例：
 * JSON 先按登记差异规范化旧版输出（空图片 "/"→""、协议相对图片按 base-url 补全协议、访问密码列不输出、角色关联视频不含播放/下载列、
 * 联想词 url 指向 Java 承接的查询串形式），再与新版逐字节比较；快捷方式正文逐字节相同；二维码两边解码出同一文本、尺寸相同 →
 * 导入 phase-diff.sql 断言可见性差异 → 表结构不变 → 删除该一次性库。ajax/data 的视频与文章对照 PHP 7.3（PHP 8 下旧版报错）。
 */
class AjaxGoldenMySqlIT {

    private static final java.util.regex.Pattern DB_NAME = java.util.regex.Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path SEED = Path.of("src", "test", "resources", "openapi", "provide", "seed.sql");
    private static final Path PHASE_DIFF = Path.of("src", "test", "resources", "openapi", "provide", "phase-diff.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Set<String> PIC_KEYS_SUFFIX = Set.of("_pic", "_pic_thumb", "_pic_slide");
    private static final List<String> MEDIA = List.of("vod_play_from", "vod_play_server", "vod_play_note", "vod_play_url",
            "vod_down_from", "vod_down_server", "vod_down_note", "vod_down_url");
    private static final List<String> PWD = List.of("vod_pwd", "vod_pwd_url", "vod_pwd_play", "vod_pwd_play_url",
            "vod_pwd_down", "vod_pwd_down_url", "art_pwd", "art_pwd_url");
    /** 只能在启动时改变的旧配置（app.search=0）：由 SiteAjaxMvcTest 覆盖。 */
    private static final Set<String> SKIPPED = Set.of("suggest.closed");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static JsonNode golden;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        try (InputStream in = AjaxGoldenMySqlIT.class.getResourceAsStream("/openapi/provide/php73-golden.json")) {
            golden = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
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
            for (String sql : statements(SEED)) {
                st.execute(sql);
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.content.search.enabled=true",
                "--maccms.site.name=示例站点",
                "--maccms.site.base-url=http://www.example.test/",
                "--maccms.legacy-config.dir=" + Path.of("..", "application").toAbsolutePath().normalize()));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
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

    record Reply(int status, String contentType, String disposition, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyIndexEntryGoldenCaseMatches() throws Exception {
        assertThat(golden.get("php").asString()).startsWith("7.3");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        boolean diffLoaded = false;
        for (JsonNode c : golden.get("cases")) {
            String id = c.get("id").asString();
            JsonNode req = c.get("request");
            if (!req.get("entry").asString().equals("index") || SKIPPED.contains(id)) {
                continue;
            }
            if (c.get("phase").asString().equals("diff") && !diffLoaded) {
                try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
                    for (String sql : statements(PHASE_DIFF)) {
                        st.execute(sql);
                    }
                }
                diffLoaded = true;
            }
            Reply r = request(req);
            byte[] php = Base64.getDecoder().decode(c.get("body").asString());
            try {
                check(id, c, r, php);
                checked++;
            } catch (AssertionError e) {
                String msg = String.valueOf(e.getMessage());
                failures.add(id + ": " + msg.substring(0, Math.min(msg.length(), 900)));
            }
        }
        System.out.println("[mysql-it] ajax golden: " + checked + " cases checked");
        assertThat(failures).as("不一致的用例").isEmpty();
        assertThat(checked).isGreaterThanOrEqualTo(30);
    }

    private static void check(String id, JsonNode c, Reply r, byte[] php) throws Exception {
        String phpText = new String(php, StandardCharsets.UTF_8);
        if (id.startsWith("desktop.")) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text()).as(id + " 快捷方式正文逐字节相同").isEqualTo(phpText);
            assertThat(r.contentType()).isEqualTo("application/octet-stream");
            assertThat(r.disposition()).startsWith("attachment; filename=\"").contains("filename*=UTF-8''");
            return;
        }
        if (id.startsWith("qrcode.")) {
            assertThat(r.status()).isEqualTo(200);
            if (php.length == 0) {
                assertThat(r.body()).as(id).isEmpty();
                return;
            }
            assertThat(r.contentType()).isEqualTo("image/png");
            String expected = c.get("request").get("query").asString().substring("url=".length());
            String url = java.net.URLDecoder.decode(expected, StandardCharsets.UTF_8);
            assertThat(decodeQr(php)).as("旧版 PNG 解码").isEqualTo(url);
            assertThat(decodeQr(r.body())).as("新版 PNG 解码").isEqualTo(url);
            assertThat(size(r.body())).as("尺寸（版本、模块 10 像素、静区 2 模块）").isEqualTo(size(php));
            return;
        }
        assertThat(r.status()).as(id).isEqualTo(200);
        assertThat(r.contentType()).as(id).isEqualTo("application/json;charset=UTF-8");
        JsonNode got = JSON.readTree(r.text());
        if (id.startsWith("diff.")) {
            diff(id, JSON.readTree(phpText), got);
            return;
        }
        JsonNode expected = normalize(JSON.readTree(phpText));
        assertThat(JSON.writeValueAsString(got)).as(id + "（规范化旧版后逐字节相同）")
                .isEqualTo(JSON.writeValueAsString(expected));
    }

    private static void diff(String id, JsonNode php, JsonNode got) {
        switch (id) {
            case "diff.data.mid9" -> {
                // 角色 3 的关联视频 30 在会员分类（游客不可见）：旧版给出整行，新版为 null
                JsonNode old3 = rows(php).stream().filter(x -> x.get("role_id").asLong() == 3).findFirst().orElseThrow();
                assertThat(old3.get("data").get("vod_name").asString()).isEqualTo("会员分类视频");
                JsonNode new3 = rows(got).stream().filter(x -> x.get("role_id").asLong() == 3).findFirst().orElseThrow();
                assertThat(new3.get("data").isNull()).isTrue();
            }
            case "diff.suggest.unpublished" -> {
                assertThat(ids(php, "id")).contains(5L);
                assertThat(ids(got, "id")).isEmpty();
            }
            case "diff.suggest.invisible" -> {
                assertThat(ids(php, "id")).contains(30L, 31L, 32L, 8L);
                assertThat(ids(got, "id")).containsExactly(8L);
            }
            default -> fail("未登记的差异用例 " + id);
        }
    }

    private static List<JsonNode> rows(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        n.get("list").forEach(out::add);
        return out;
    }

    private static List<Long> ids(JsonNode n, String key) {
        List<Long> out = new ArrayList<>();
        for (JsonNode row : rows(n)) {
            out.add(row.get(key).asLong());
        }
        return out;
    }

    /** 旧版输出按 16 文档第 7 节的登记差异改写成新版应有的形态。 */
    static JsonNode normalize(JsonNode php) {
        if (!(php instanceof ObjectNode o) || !o.has("list")) {
            return php;
        }
        if (o.has("url")) {
            // /index.php/<模块>/search/wd/mac_wd.html → /index.php/<模块>/search.html?wd=mac_wd
            o.put("url", o.get("url").asString().replace("/search/wd/mac_wd.html", "/search.html?wd=mac_wd"));
        }
        JsonNode list = o.get("list");
        if (list instanceof ArrayNode a) {
            a.forEach(AjaxGoldenMySqlIT::normalizeRow);
        } else if (list instanceof ObjectNode m) {
            m.properties().forEach(e -> normalizeRow(e.getValue()));
        }
        return o;
    }

    private static void normalizeRow(JsonNode row) {
        ObjectNode r = (ObjectNode) row;
        PWD.forEach(r::remove);
        List<String> keys = new ArrayList<>();
        r.propertyNames().forEach(keys::add);
        for (String k : keys) {
            boolean pic = k.equals("pic") || PIC_KEYS_SUFFIX.stream().anyMatch(k::endsWith);
            if (pic && r.get(k).isString()) {
                r.put(k, picture(r.get(k).asString()));
            }
        }
        if (r.has("data") && r.get("data").isObject()) {
            ObjectNode d = (ObjectNode) r.get("data");
            PWD.forEach(d::remove);
            MEDIA.forEach(d::remove);
        }
    }

    /** 空值旧版输出站点根路径 "/"，新版为空串；协议相对地址新版按 base-url 的协议补全（06 文档，MACC-11）。 */
    private static String picture(String old) {
        if (old.equals("/")) {
            return "";
        }
        if (old.startsWith("//")) {
            return "http:" + old;
        }
        return old;
    }

    private static String decodeQr(byte[] png) throws Exception {
        var img = ImageIO.read(new ByteArrayInputStream(png));
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))))
                .getText();
    }

    private static String size(byte[] png) throws IOException {
        var img = ImageIO.read(new ByteArrayInputStream(png));
        return img.getWidth() + "x" + img.getHeight();
    }

    private static Reply request(JsonNode req) throws IOException, InterruptedException {
        String query = req.get("query").asString();
        String uri = baseUrl + "/index.php" + req.get("path").asString() + (query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).GET();
        if (req.has("headers")) {
            for (var e : req.get("headers").properties()) {
                b.header(e.getKey(), e.getValue().asString());
            }
        }
        HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(""),
                resp.headers().firstValue("Content-Disposition").orElse(""), resp.body());
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
