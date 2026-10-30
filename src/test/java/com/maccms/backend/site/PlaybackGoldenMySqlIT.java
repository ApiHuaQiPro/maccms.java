package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 播放/下载四个入口与 PHP 黄金样本的逐条对照（B05，./mvnw verify -Pmysql-it）。流程：外部管理账号新建一次性库
 * maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构 → 导入与 PHP 黄金样本相同的合成数据（site/play/seed.sql）→ 按用例的配置
 * 启动完整应用（旧配置目录为仓库 application/extra 的临时副本，只改 app.encrypt、app.player_sort）→ 每个用例把游客组权限改成样本中的
 * 形态（只写这个一次性库）→ 真实 HTTP 请求 → 比对判定与 player_aaaa（逐字段）→ 比对表结构指纹 → 只删除该库并确认已不存在。
 *
 * <p>与旧版的已登记差异按 15 文档第 11 节断言：旧 vod/player 内页不中止权限检查、会把地址交给无权限的游客，新版按播放页同样判定
 * （403）；下载地址页显示解码后的地址（旧版是否解码取决于模板）。另在真实 SQL 上验证访问密码：BINARY 比较、签名票据、改密码后旧票据失效。
 * 不连接、不读写任何已有库（包括 maccms_dev），日志不含密码与票据。
 */
@ExtendWith(OutputCaptureExtension.class)
class PlaybackGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path SEED = Path.of("src", "test", "resources", "site", "play", "seed.sql");
    private static final String SECRET = "synthetic-it-secret-0123456789-abcdefghijk";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static JsonNode golden;
    private static Path tempRoot;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        try (InputStream in = PlaybackGoldenMySqlIT.class.getResourceAsStream("/site/play/php-golden.json")) {
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
        tempRoot = Files.createTempDirectory("maccms-play-it-");
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (tempRoot != null) {
                try (Stream<Path> s = Files.walk(tempRoot)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
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

    // ------------------------------------------------------------------ 用例

    @Test
    void everyGoldenCaseMatchesTheLegacyDecisionAndPlayerData(CapturedOutput output) throws Exception {
        assertThat(golden.get("php").asString()).startsWith("8.0");
        Map<String, List<JsonNode>> byConfig = new LinkedHashMap<>();
        for (JsonNode c : golden.get("cases")) {
            // player_sort 只影响外层页的分组顺序；=1 只在默认配置下再跑一轮，其余配置按 =0
            if (c.get("playerSort").asString().equals("1") && c.get("config").size() > 0) {
                continue;
            }
            byConfig.computeIfAbsent(c.get("playerSort").asString() + " " + c.get("config"), k -> new ArrayList<>()).add(c);
        }
        int checked = 0;
        int players = 0;
        for (List<JsonNode> group : byConfig.values()) {
            JsonNode first = group.getFirst();
            try (App app = App.start(first.get("config"), first.get("playerSort").asString())) {
                for (JsonNode c : group) {
                    setPopedom(c.get("popedomVariant").asString());
                    players += check(app, c);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(players).as("逐字段对照的 player_aaaa 个数").isGreaterThan(30);
        assertThat(output.getAll()).doesNotContain("Ab9").doesNotContain(SECRET);
    }

    /** @return 逐字段对照了 player_aaaa 时为 1 */
    private static int check(App app, JsonNode c) throws Exception {
        String id = c.get("id").asString();
        String entry = c.get("entry").asString();
        String result = c.get("result").asString();
        int code = c.get("popedom").get("code").asInt();
        long vod = c.get("vod").asLong();
        int sid = c.get("sid").asInt();
        int nid = c.get("nid").asInt();
        String path = switch (entry) {
            case "play" -> "/index.php/vod/play/id/" + vod + "/sid/" + sid + "/nid/" + nid + ".html";
            case "player" -> "/index.php/vod/player/id/" + vod + "/nid/" + nid + "/sid/" + sid + ".html";
            case "down" -> "/index.php/vod/down/id/" + vod + "/sid/" + sid + "/nid/" + nid + ".html";
            default -> "/index.php/vod/downer/id/" + vod + "/nid/" + nid + "/sid/" + sid + ".html";
        };
        Page p = app.get(path, null);
        String where = id + " " + c.get("config") + " sort=" + c.get("playerSort").asString();
        assertThat(p.headers().getOrDefault("cache-control", List.of())).as(where).contains("no-store");
        switch (entry) {
            case "play", "down" -> {
                switch (result) {
                    case "error", "confirm" -> assertThat(p.status()).as(where).isEqualTo(403);
                    case "copyright" -> {
                        assertThat(p.status()).as(where).isEqualTo(403);
                        assertThat(p.doc().selectFirst(".access-notice__message").text()).as(where).contains("版权");
                    }
                    default -> {
                        assertThat(p.status()).as(where).isEqualTo(200);
                        assertThat(p.body()).as(where).doesNotContain("example.invalid");
                        if (entry.equals("play")) {
                            assertThat(p.doc().select(".player-page__trial").isEmpty()).as(where).isEqualTo(code != 3002);
                        }
                    }
                }
                return 0;
            }
            case "player" -> {
                // 新版内页按播放页的判定（旧版 $pe=1 不中止，15 文档第 11 节差异）
                if (code == 3001 || code == 3003) {
                    assertThat(p.status()).as(where).isEqualTo(403);
                    assertThat(p.body()).as(where).doesNotContain("example.invalid");
                    return 0;
                }
                if (result.equals("copyright") || result.equals("password")) {
                    assertThat(p.status()).as(where).isEqualTo(403);
                    assertThat(p.body()).as(where).doesNotContain("example.invalid");
                    return 0;
                }
                assertThat(p.status()).as(where).isEqualTo(200);
                Element stage = p.doc().selectFirst("#player-stage");
                assertThat(stage).as(where).isNotNull();
                JsonNode actual = JSON.readTree(stage.attr("data-player")).get("player");
                assertThat(actual).as(where + " player_aaaa 逐字段").isEqualTo(c.get("playerInfo"));
                int trial = code == 3002 ? c.get("playerInfo").get("trysee").asInt() : 0;
                assertThat(stage.attr("data-trial-minutes")).as(where).isEqualTo(Integer.toString(trial));
                return 1;
            }
            default -> {
                if (result.equals("error")) {
                    assertThat(p.status()).as(where).isEqualTo(403);
                    return 0;
                }
                if (result.equals("password")) {
                    assertThat(p.status()).as(where).isEqualTo(403);
                    assertThat(p.doc().select("form.password-page__form")).as(where).hasSize(1);
                    assertThat(p.body()).as(where).doesNotContain("example.invalid");
                    return 0;
                }
                assertThat(p.status()).as(where).isEqualTo(200);
                if (c.get("playerInfo").get("encrypt").asInt() == 0) {
                    String url = c.get("playerInfo").get("url").asString();
                    Element link = p.doc().selectFirst("a.download-page__link");
                    Element text = p.doc().selectFirst("code.download-page__text");
                    assertThat(link != null ? link.attr("href") : text.text()).as(where + " 下载地址").isEqualTo(url);
                }
                return 0;
            }
        }
    }

    @Test
    void passwordsAreComparedInSqlAndTicketsDieWhenThePasswordChanges(CapturedOutput output) throws Exception {
        setPopedom("full");
        JsonNode expected = null;
        for (JsonNode c : golden.get("cases")) {
            if (c.get("id").asString().equals("player.v4.pwd") && c.get("playerSort").asString().equals("0")) {
                expected = c.get("playerInfo");
            }
        }
        assertThat(expected).isNotNull();
        String player = "/index.php/vod/player/id/4/nid/1/sid/1.html";
        String downer = "/index.php/vod/downer/id/4/nid/1/sid/1.html";
        try (App app = App.start(JSON.readTree("{}"), "0")) {
            assertThat(app.get(player, null).status()).isEqualTo(403);
            // BINARY 比较：大小写、尾随空格都不相等；PHP 的数字字符串宽松相等不复刻
            for (String wrong : List.of("ab9", "Ab9 ", "AB9", "dl7", "")) {
                assertThat(app.post(player, wrong).status()).as(wrong).isEqualTo(403);
            }
            Page ok = app.post(player, "Ab9");
            assertThat(ok.status()).isEqualTo(303);
            String cookie = ok.headers().get("set-cookie").getFirst();
            assertThat(cookie).startsWith("maccms_pwd_v4_4=").contains("HttpOnly", "SameSite=Lax", "Path=/");
            String pair = cookie.substring(0, cookie.indexOf(';'));
            Page played = app.get(player, pair);
            assertThat(played.status()).isEqualTo(200);
            assertThat(JSON.readTree(played.doc().selectFirst("#player-stage").attr("data-player")).get("player"))
                    .isEqualTo(expected);
            // 票据只对播放用途有效
            assertThat(app.get(downer, pair.replace("maccms_pwd_v4_4", "maccms_pwd_v5_4")).status()).isEqualTo(403);
            Page down = app.post(downer, "dl7");
            assertThat(down.status()).isEqualTo(303);
            assertThat(app.get(downer, down.headers().get("set-cookie").getFirst().split(";")[0]).status()).isEqualTo(200);
            // 改密码后旧票据立即失效（凭据在 SQL 中与当前密码比较）；改回后又有效
            try (Connection c = admin(dbName); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE mac_vod SET vod_pwd_play='Zz1' WHERE vod_id=4");
                assertThat(app.get(player, pair).status()).isEqualTo(403);
                st.executeUpdate("UPDATE mac_vod SET vod_pwd_play='Ab9' WHERE vod_id=4");
                assertThat(app.get(player, pair).status()).isEqualTo(200);
                // 未审核的视频不校验密码
                st.executeUpdate("UPDATE mac_vod SET vod_status=0 WHERE vod_id=4");
                assertThat(app.post(player, "Ab9").status()).isEqualTo(404);
                st.executeUpdate("UPDATE mac_vod SET vod_status=1 WHERE vod_id=4");
            }
        }
        assertThat(output.getAll()).doesNotContain("Ab9").doesNotContain("dl7").doesNotContain("Zz1");
    }

    // ------------------------------------------------------------------ 应用

    private record Page(int status, String body, Document doc, Map<String, List<String>> headers) {
    }

    private static final class App implements AutoCloseable {
        private final ConfigurableApplicationContext context;
        private final String base;

        private App(ConfigurableApplicationContext context) {
            this.context = context;
            this.base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        static App start(JsonNode config, String playerSort) throws IOException {
            Path dir = legacyConfig(config, playerSort);
            List<String> args = new ArrayList<>(List.of(
                    "--spring.datasource.url=" + jdbcUrlFor(dbName),
                    "--spring.datasource.username=" + adminUser,
                    "--server.port=0",
                    "--server.address=127.0.0.1",
                    "--maccms.site.enabled=true",
                    "--maccms.site.name=合成站",
                    "--maccms.site.base-url=http://maccms.local.test/",
                    "--maccms.legacy-config.dir=" + dir,
                    "--maccms.site.password-tickets.secret=" + SECRET,
                    "--maccms.browser-external.w1=true",
                    "--maccms.browser-external.w2=true"));
            String status = text(config, "app.copyright_status", "1");
            args.add("--maccms.content.playback.copyright-status=" + status);
            args.add("--maccms.content.copyright-hides-detail=" + status.equals("2"));
            args.add("--maccms.content.playback.trysee=" + text(config, "user.trysee", "0"));
            args.add("--maccms.content.playback.vod-points-type=" + text(config, "user.vod_points_type", "1"));
            args.add("--maccms.content.enforce-guest-popedom=" + !text(config, "user.status", "1").equals("0"));
            if ("root".equalsIgnoreCase(adminUser)) {
                args.add("--maccms.datasource.allow-root=true");
            }
            System.setProperty("spring.datasource.password", adminPassword);
            return new App(new SpringApplicationBuilder(MaccmsBackendApplication.class).run(args.toArray(String[]::new)));
        }

        Page get(String path, String cookie) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
            if (cookie != null) {
                b.header("Cookie", cookie);
            }
            return page(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }

        Page post(String path, String pwd) throws Exception {
            HttpRequest r = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("pwd=" + java.net.URLEncoder.encode(pwd, StandardCharsets.UTF_8)))
                    .build();
            return page(HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }

        private static Page page(HttpResponse<String> r) {
            return new Page(r.statusCode(), r.body(), Jsoup.parse(r.body()), r.headers().map());
        }

        @Override
        public void close() {
            context.close();
        }
    }

    /** 仓库旧配置的临时副本，只改 app.encrypt、app.player_sort 两项（文本替换 var_export 的固定写法）。 */
    private static Path legacyConfig(JsonNode config, String playerSort) throws IOException {
        Path dir = Files.createTempDirectory(tempRoot, "cfg-");
        Path extra = Files.createDirectories(dir.resolve("extra"));
        for (String f : List.of("vodplayer.php", "voddowner.php", "vodserver.php")) {
            Files.copy(Path.of("php", "application", "extra", f), extra.resolve(f));
        }
        String maccms = Files.readString(Path.of("php", "application", "extra", "maccms.php"), StandardCharsets.UTF_8);
        String encrypt = text(config, "app.encrypt", "0");
        String replaced = maccms.replace("'encrypt' => '0',", "'encrypt' => '" + encrypt + "',")
                .replace("'player_sort' => '1',", "'player_sort' => '" + playerSort + "',");
        assertThat(replaced.contains("'encrypt' => '" + encrypt + "',")
                && replaced.contains("'player_sort' => '" + playerSort + "',")).isTrue();
        Files.writeString(extra.resolve("maccms.php"), replaced, StandardCharsets.UTF_8);
        return dir;
    }

    private static String text(JsonNode config, String key, String fallback) {
        return Optional.ofNullable(config.get(key)).map(JsonNode::asString).orElse(fallback);
    }

    private static void setPopedom(String variant) throws SQLException {
        JsonNode v = golden.get("popedom").get(variant);
        try (Connection c = admin(dbName);
             PreparedStatement ps = c.prepareStatement("UPDATE mac_group SET group_type=?, group_popedom=? WHERE group_id=1")) {
            ps.setString(1, v.get("groupType").asString());
            ps.setString(2, v.get("groupPopedom").asString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 数据库

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
