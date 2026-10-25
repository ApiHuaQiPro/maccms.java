package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.comment.application.view.PostReply;
import com.maccms.backend.comment.application.view.Poster;
import com.maccms.backend.common.captcha.CaptchaSettings;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentCounterService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.CounterTarget;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.VodSearchCache;
import com.maccms.backend.content.application.VodSearchField;
import com.maccms.backend.content.application.view.CounterOutcome;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.openapi.application.ProvideSettings;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.site.web.SiteSessions;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B11 评论、留言与内容计数的真实 MySQL + 真实 HTTP 对照（./mvnw verify -Pmysql-it；docs/architecture/20-b11-comment-gbook-counters.md 第 11 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql、B06 合成内容 seed.sql 与黄金的固定设定 → 表结构指纹与
 * 快照 S0 → 以真实 Tomcat 启动应用（site、openapi、可写数据源打开；旧配置目录是临时目录，按用例改写 {@code extra/maccms.php}，B11 的设置与
 * Provide 的设置按请求读取；容器里的 {@link Clock} 由本测试固定到黄金样本的请求时刻）→
 * ① 逐条复放 PHP 8.0 与 7.3 的 HTTP 黄金：先执行黄金记录的初始语句，再以原始套接字发同样的请求（Host、Cookie、Referer、X-Requested-With），
 * 比较状态码、JSON 正文、Set-Cookie（去掉 expires 日期）与全部相关表的行（自增主键与"现在"规范化）；有意差异（第 9 节）按新版结果断言 →
 * ② 视频搜索缓存复放 → ③ 并发 → ④ 只用 DML 恢复到 S0、逐行 diff 为空、表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InteractionGoldenMySqlIT {

    private static final java.util.regex.Pattern DB_NAME = java.util.regex.Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path SEED_SQL = Path.of("src", "test", "resources", "openapi", "provide", "seed.sql");
    private static final Path REPO_APPLICATION = Path.of("..", "application").toAbsolutePath().normalize();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOLDEN80 = "interaction/php80-golden.json";
    private static final String GOLDEN73 = "interaction/php73-golden.json";
    private static final List<String> TABLES = List.of("mac_comment", "mac_gbook", "mac_visit", "mac_vod_search",
            "mac_user", "mac_vod", "mac_art", "mac_topic", "mac_actor", "mac_role", "mac_website");
    /** 自增主键：新库里与黄金运行时的值不同，比较时去掉。 */
    private static final Map<String, String> AUTO_KEYS = Map.of("mac_comment", "comment_id", "mac_gbook", "gbook_id",
            "mac_visit", "visit_id");
    private static final Set<String> NOW_COLUMNS = Set.of("comment_time", "gbook_time", "visit_time", "vod_time_hits",
            "art_time_hits", "topic_time_hits", "actor_time_hits", "role_time_hits", "website_time_hits",
            "website_time_referer", "search_last_hit_time", "search_update_time");
    private static final Map<String, String> PREFIX = Map.of("mac_vod", "vod", "mac_art", "art", "mac_topic", "topic",
            "mac_actor", "actor", "mac_role", "role", "mac_website", "website");
    private static final List<String> COUNTER_COLUMNS = List.of("hits", "hits_day", "hits_week", "hits_month",
            "time_hits", "up", "down", "score", "score_all", "score_num");

    /**
     * 有意差异（20 文档第 9 节）：用例 → 新版的 JSON 正文；行、cookie 按"没有写入、没有 cookie"断言，另有说明的除外（{@link #rowsExpectation}）。
     */
    private static final Map<String, String> JAVA_JSON = new LinkedHashMap<>();
    /** 新版与旧版写入不同、但仍有写入的用例：只断言正文与 cookie，行由 {@link #rowsExpectation} 给出。 */
    private static final Set<String> CUSTOM_ROWS = Set.of("cs.forged-cookie", "cs.forged-id-only", "cs.mass-assign",
            "gs.forged-cookie", "gs.mass-assign", "gs.mass-assign-guest", "cs.ip-forwarded", "ref.update",
            "ref.referer-contains-host", "ref.visit-other-ip", "ref.visit-yesterday", "ref.visit-member-row",
            "ref.visit-limit-config", "ref.read");
    /** 非 ajax 的提交：新版返回提示页（D-B11-10），正文含该提示；写入与 cookie 与旧版相同。 */
    private static final Map<String, String> JAVA_PAGE = Map.of("cs.non-ajax", "感谢你的留言！",
            "gs.non-ajax", "感谢你的留言！", "vc.bad-type-non-ajax", "参数错误", "vc.wrong-non-ajax", "验证码错误",
            "vc.ok-non-ajax", "ok");
    /** 不在 HTTP 层复放的用例（原因见 20 文档第 11 节）：客户端 IP 不是本机、码表不是数字的验证码。 */
    private static final Set<String> SKIPPED = Set.of("cs.ip", "cs.ipv6", "gs.ip", "ref.visit-other-ip",
            "vc.lowercase");

    static {
        String saveFailed = "{\"code\":1002,\"msg\":\"保存失败!：\"}";
        // D-B11-4：comment_mid 按字节精确比较
        for (String id : List.of("cs.mid-bad-3031", "cs.mid-bad-312e30", "cs.mid-bad-2031")) {
            JAVA_JSON.put(id, "{\"code\":1006,\"msg\":\"模型mid错误\"}");
        }
        // D-B11-5：严格模式拒绝的写入（旧版 500，同样没有写入）
        for (String id : List.of("cs.rid-negative", "cs.long-over", "cs.long-entity-over", "cs.emoji", "cs.nick-long",
                "cr.max", "cd.max", "gs.rid-text", "gs.rid-negative", "gs.long-over", "gs.emoji", "hits.update.max",
                "digg.max", "score.negative-big", "score.big", "score.hundred", "score.max-all")) {
            JAVA_JSON.put(id, saveFailed);
        }
        JAVA_JSON.put("ref.url-long", "{\"code\":103,\"msg\":\"来路记录失败，请重试\"}");
        // D-B11-12：空域名按 PHP 7.3 拒绝；D-B11-6：域名按字面量匹配
        JAVA_JSON.put("ref.no-domain", "{\"code\":1003,\"msg\":\"参数错误\"}");
        JAVA_JSON.put("ref.wildcard-percent", "{\"code\":1002,\"msg\":\"获取失败\"}");
        // PHP 8 类型错误（500）、7.3 正常：按 7.3（同 D-B10-8）
        JAVA_JSON.put("cs.array-content", "{\"code\":1001,\"msg\":\"参数错误：内容必须\"}");
    }

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static DatabaseSnapshot s0;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static int port;
    private static Path configDir;
    private static PhpArray repoConfig;
    private static final MutableClock CLOCK = new MutableClock();

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        JsonNode golden = load(GOLDEN80);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            for (String sql : statements(SEED_SQL)) {
                st.execute(sql);
            }
            for (JsonNode sql : golden.get("fixture")) {
                st.execute(sql.asString());
            }
            s0 = DatabaseSnapshot.take(c, TABLES);
        }
        // 旧配置目录：仓库 application/ 中 B02 能读的文件的副本；extra/maccms.php 按用例改写
        configDir = Files.createTempDirectory("maccms-b11-config");
        Files.createDirectories(configDir.resolve("extra"));
        for (LegacyConfigFile f : LegacyConfigFile.values()) {
            Path src = REPO_APPLICATION.resolve(f.relativePath());
            if (Files.isRegularFile(src)) {
                Files.copy(src, configDir.resolve(f.relativePath()));
            }
        }
        repoConfig = PhpConfigFiles.read(REPO_APPLICATION.resolve("extra/maccms.php"));
        writeConfig(golden.get("baseConfig"), null);
        CLOCK.set(Instant.now().getEpochSecond());
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + jdbcUrlFor(dbName), "--spring.datasource.username=" + adminUser,
                "--maccms.write.enabled=true", "--maccms.write.url=" + jdbcUrlFor(dbName),
                "--maccms.write.username=" + adminUser, "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true", "--maccms.content.search.enabled=true",
                "--maccms.openapi.enabled=true", "--maccms.site.name=示例站点",
                "--maccms.site.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + configDir));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            SpringApplicationBuilder b = new SpringApplicationBuilder(MaccmsBackendApplication.class);
            b.initializers(ctx -> {
                ctx.getBeanFactory().registerSingleton("b11TestClock", CLOCK);
                // Provide 的旧配置按请求读取（B06 的测试钩子），这样 detail_inc_hits 能按用例开关
                ctx.getBeanFactory().registerSingleton("b11ProvideSettings", (ProvideSettingsSource) () ->
                        ProvideSettings.from(ctx.getBean(LegacyConfigService.class).read(LegacyConfigFile.MACCMS)));
            });
            app = b.run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (configDir != null) {
            try (var s = Files.walk(configDir)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            Map<String, String> schemaNow = null;
            if (schemaAtImport != null) {
                try (Connection c = admin(dbName)) {
                    schemaNow = SchemaFingerprint.of(c, dbName);
                }
            }
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            if (schemaNow != null) {
                assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------ ① HTTP 黄金复放

    @Test
    @Order(1)
    void httpCasesMatchPhp80Golden() throws Exception {
        replayAll(GOLDEN80);
    }

    @Test
    @Order(2)
    void httpCasesMatchPhp73Golden() throws Exception {
        replayAll(GOLDEN73);
    }

    private void replayAll(String resource) throws Exception {
        JsonNode golden = load(resource);
        Map<String, JsonNode> by73 = new LinkedHashMap<>();
        load(GOLDEN73).get("cases").forEach(c -> by73.put(c.get("id").asString(), c));
        int compared = 0;
        for (JsonNode c : golden.get("cases")) {
            String id = c.get("id").asString();
            if (SKIPPED.contains(id)) {
                continue;
            }
            // 两版 PHP 结果不同的用例：新版的取向见 20 文档第 9 节（Provide 按 8.0；其余按 7.3 或第 9 节的新版结果）
            JsonNode expected = c;
            if (id.startsWith("provide.") && resource.equals(GOLDEN73)) {
                expected = load(GOLDEN80).get("cases").valueStream().filter(x -> x.get("id").asString().equals(id))
                        .findFirst().orElseThrow();
            } else if (!id.startsWith("provide.") && by73.containsKey(id)) {
                expected = c.get("status").asInt() >= 500 && by73.get(id).get("status").asInt() < 500 ? by73.get(id) : c;
            }
            replay(golden.get("php").asString() + " " + id, golden, c, expected);
            compared++;
        }
        System.out.println("[mysql-it] B11 http golden " + golden.get("php").asString() + " cases compared: " + compared);
        assertThat(compared).isGreaterThan(250);
    }

    private void replay(String what, JsonNode golden, JsonNode c, JsonNode expected) throws Exception {
        String id = c.get("id").asString();
        writeConfig(golden.get("baseConfig"), c.get("config"));
        long started = c.get("started").asLong();
        CLOCK.set(started);
        try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
            for (JsonNode sql : c.get("setup")) {
                st.execute(sql.asString());
            }
        }
        JsonNode req = c.get("request");
        List<String> cookies = new ArrayList<>();
        if (!req.get("as").isNull()) {
            cookies.add(userCookie(req.get("as").asLong()));
        }
        if (!req.get("cookie").isNull()) {
            cookies.add(req.get("cookie").asString());
        }
        SessionState session = null;
        if (!req.get("captcha").isNull() || req.get("session").size() > 0) {
            session = app.getBean(SiteSessions.class).store().create();
            if (!req.get("captcha").isNull()) {
                issueCaptcha(session, req.get("captcha").asString(), started);
            }
            for (Map.Entry<String, JsonNode> e : req.get("session").properties()) {
                session.put(e.getKey(), e.getValue().asString());
            }
            cookies.add("PHPSESSID=" + session.id());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (req.get("ajax").asBoolean()) {
            headers.put("X-Requested-With", "XMLHttpRequest");
        }
        if (!cookies.isEmpty()) {
            headers.put("Cookie", String.join("; ", cookies));
        }
        for (Map.Entry<String, JsonNode> e : req.get("headers").properties()) {
            if (!e.getKey().equals("X-Forwarded-For") && !e.getKey().equals("Client-IP")) {
                headers.put(e.getKey(), e.getValue().asString());
            }
        }
        String entry = req.get("entry").asString();
        String query = req.get("query").asString();
        String target = "/" + entry + ".php" + req.get("path").asString() + (query.isEmpty() ? "" : "?" + query);
        Response r = send(req.get("method").asString(), target, headers, req.get("post").asString());
        Map<String, List<Map<String, String>>> rows = readRows(started, started);

        if (id.startsWith("provide.")) {
            // Provide 的正文由 B06 黄金锁定；这里只比较计数写入
            assertThat(r.status()).as(what + " status").isEqualTo(expected.get("status").asInt() >= 500 ? 200
                    : expected.get("status").asInt());
            assertThat(rows.get("mac_vod")).as(what + " mac_vod").isEqualTo(goldenRows(expected, "mac_vod"));
            return;
        }
        if (JAVA_PAGE.containsKey(id)) {
            assertThat(r.status()).as(what + " status").isEqualTo(200);
            assertThat(r.contentType()).as(what + " type").startsWith("text/html");
            assertThat(r.body()).as(what + " page").contains(JAVA_PAGE.get(id));
            assertThat(cookieNames(r.setCookies())).as(what + " cookies").isEqualTo(cookieNames(goldenCookies(expected)));
            assertRows(what, expected, rows);
            assertSession(what, expected, session);
            return;
        }
        if (JAVA_JSON.containsKey(id)) {
            assertThat(r.status()).as(what + " status").isEqualTo(200);
            assertThat(r.body()).as(what + " body").isEqualTo(JAVA_JSON.get(id));
            assertThat(cookieNames(r.setCookies())).as(what + " cookies")
                    .isEqualTo(id.startsWith("ref.") || id.equals("cs.array-content") ? cookieNames(goldenCookies(expected))
                            : List.of());
            Map<String, List<Map<String, String>>> unchanged = setupRows(expected, started);
            assertThat(rows).as(what + " 没有写入").isEqualTo(unchanged);
            return;
        }
        assertThat(r.status()).as(what + " status").isEqualTo(expected.get("status").asInt());
        String goldenBody = new String(Base64.getDecoder().decode(expected.get("body").asString()), StandardCharsets.UTF_8);
        assertThat(r.body()).as(what + " body").isEqualTo(goldenBody);
        assertThat(normalizeCookies(r.setCookies())).as(what + " cookies")
                .isEqualTo(normalizeCookies(goldenCookies(expected)));
        if (CUSTOM_ROWS.contains(id)) {
            assertThat(rows).as(what + " rows").isEqualTo(rowsExpectation(id, expected, started));
        } else {
            assertRows(what, expected, rows);
        }
        assertSession(what, expected, session);
    }

    private void assertRows(String what, JsonNode expected, Map<String, List<Map<String, String>>> rows) {
        Map<String, List<Map<String, String>>> g = new TreeMap<>();
        for (String t : TABLES) {
            g.put(t, goldenRows(expected, t));
        }
        assertThat(rows).as(what + " rows").isEqualTo(g);
    }

    private void assertSession(String what, JsonNode expected, SessionState session) {
        JsonNode after = expected.get("sessionAfter");
        if (session == null || after == null || after.isNull() || !after.has("think")) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : after.get("think").properties()) {
            assertThat(session.get(e.getKey(), String.class)).as(what + " session " + e.getKey())
                    .contains(e.getValue().asString());
        }
    }

    /**
     * 新版写入与旧版不同的用例（20 文档第 9 节）：以黄金行为底，按新版规则改动受影响的列。
     */
    private Map<String, List<Map<String, String>>> rowsExpectation(String id, JsonNode g, long now) {
        Map<String, List<Map<String, String>>> rows = new TreeMap<>();
        for (String t : TABLES) {
            rows.put(t, goldenRows(g, t));
        }
        switch (id) {
            case "cs.forged-cookie", "cs.forged-id-only" -> {
                // D-B11-2：cookie 未通过 label_user 校验，按游客
                Map<String, String> row = rows.get("mac_comment").get(0);
                row.put("user_id", "0");
                row.put("comment_name", "游客");
            }
            case "gs.forged-cookie" -> {
                Map<String, String> row = rows.get("mac_gbook").get(0);
                row.put("user_id", "0");
                row.put("gbook_name", "游客");
            }
            case "cs.mass-assign" -> {
                // D-B11-3：comment_reply、comment_report 不取客户端提交的值
                Map<String, String> row = rows.get("mac_comment").get(0);
                row.put("comment_reply", "0");
                row.put("comment_report", "0");
            }
            case "gs.mass-assign", "gs.mass-assign-guest" -> rows.get("mac_gbook").get(0).put("gbook_reply_time", "0");
            case "cs.ip-forwarded" -> rows.get("mac_comment").get(0).put("comment_ip", "2130706433"); // D2 ④：只认对端
            default -> {
                // ref.*：D-B11-7——同一域名前缀的第二个网址（website_id=2）各自计数，而不是复制第一行算出的值
                Map<String, String> before = setupRows(g, now).get("mac_website").get(1);
                Map<String, String> row = rows.get("mac_website").get(1);
                if (!row.equals(before) && !id.equals("ref.read")) {
                    var p = ContentCounterService.periods(now);
                    long old = Long.parseLong(before.get("website_time_referer"));
                    long month = old >= p.monthStart() && old < p.monthEnd()
                            ? Long.parseLong(before.get("website_referer_month")) + 1 : 1;
                    long week = old >= p.weekStart() && old <= p.weekEnd()
                            ? Long.parseLong(before.get("website_referer_week")) + 1 : 1;
                    long day = old >= p.dayStart() && old < p.dayEnd()
                            ? Long.parseLong(before.get("website_referer_day")) + 1 : 1;
                    row.put("website_referer", Long.toString(Long.parseLong(before.get("website_referer")) + 1));
                    row.put("website_referer_day", Long.toString(day));
                    row.put("website_referer_week", Long.toString(week));
                    row.put("website_referer_month", Long.toString(month));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ ② 视频搜索缓存

    @Test
    @Order(3)
    void vodSearchCacheMatchesPhpGolden() throws Exception {
        ContentReadService content = app.getBean(ContentReadService.class);
        for (String resource : List.of(GOLDEN80, GOLDEN73)) {
            JsonNode golden = load(resource);
            int compared = 0;
            try (Connection c = admin(dbName); Statement st = c.createStatement()) {
                st.execute("DELETE FROM mac_vod_search");
            }
            for (JsonNode s : golden.get("search")) {
                String label = s.get("label").asString();
                JsonNode p = s.get("param");
                // wd.long：140 个字超过新版关键字的 64 字上限（09 方案），不复放；wd.spaces：SSR 先去掉首尾空白（B05），按去掉后的值
                if (label.equals("wd.long")) {
                    continue;
                }
                writeConfig(golden.get("baseConfig"), s.get("config"));
                CLOCK.set(s.get("started").asLong());
                try (Connection c = admin(dbName); Statement st = c.createStatement()) {
                    for (JsonNode sql : s.get("setup")) {
                        st.execute(sql.asString());
                    }
                }
                VideoFilter f = new VideoFilter(text(p, "wd"), null, null, null,
                        p.has("level") ? Integer.parseInt(p.get("level").asString()) : null, text(p, "class"), null, null,
                        null, text(p, "actor"), text(p, "director"), text(p, "tag"), text(p, "name"), null);
                var page = content.listVideos(new VideoListQuery(null, VideoSort.ID, false, 1, 20, f, false,
                        VideoLegacyConditions.builder().searchCache(true).build()));
                List<Long> ids = page.items().stream().map(v -> (long) v.id()).toList();
                List<Long> expected = new ArrayList<>();
                s.get("ids").forEach(n -> expected.add(n.asLong()));
                assertThat(ids).as(resource + " " + label + " ids").isEqualTo(expected);
                assertThat(searchRows(s.get("started").asLong())).as(resource + " " + label + " mac_vod_search")
                        .isEqualTo(goldenSearchRows(s));
                compared++;
            }
            assertThat(compared).isEqualTo(golden.get("search").size() - 1);
        }
    }

    // ------------------------------------------------------------------ ③ 并发

    @Test
    @Order(4)
    void concurrentCountersNeverLoseUpdates() throws Exception {
        ContentCounterService counters = app.getBean(ContentCounterService.class);
        CommentService comments = app.getBean(CommentService.class);
        long now = Instant.now().getEpochSecond();
        CLOCK.set(now);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("UPDATE mac_vod SET vod_hits = 0, vod_hits_day = 0, vod_hits_week = 0, vod_hits_month = 0,"
                    + " vod_time_hits = " + now + ", vod_up = 0, vod_down = 0, vod_score = '0.0', vod_score_all = 0,"
                    + " vod_score_num = 0 WHERE vod_id = 1");
            st.execute("UPDATE mac_website SET website_referer = 0, website_referer_day = 0, website_referer_week = 0,"
                    + " website_referer_month = 0, website_time_referer = " + now + " WHERE website_id IN (1, 2)");
            st.execute("DELETE FROM mac_comment");
            st.execute("INSERT INTO mac_comment (comment_id, comment_mid, comment_rid, comment_name, comment_content,"
                    + " comment_time) VALUES (900, 1, 1, '甲', '一', 1700000000)");
            st.execute("DELETE FROM mac_visit");
            st.execute("DELETE FROM mac_vod_search");
        }
        // 点击：100 个并发都计入，日/周/月同步 +100
        var hits = ConcurrentWrites.run(100, i -> counters.hit(CounterTarget.VOD, 1).status());
        assertThat(hits.count(CounterOutcome.Status.FOUND)).isEqualTo(100);
        // 顶 60、踩 40
        var digg = ConcurrentWrites.run(100, i -> counters.digg(CounterTarget.VOD, 1, i < 60).status());
        assertThat(digg.count(CounterOutcome.Status.FOUND)).isEqualTo(100);
        // 评分：50 次，分值 1..10 循环，总分 275
        var score = ConcurrentWrites.run(50, i -> counters.score(CounterTarget.VOD, 1, i % 10 + 1).status());
        assertThat(score.count(CounterOutcome.Status.FOUND)).isEqualTo(50);
        // Provide detail_inc_hits：30 次
        var provide = ConcurrentWrites.run(30, i -> counters.provideDetailHit(1));
        assertThat(provide.count(true)).isEqualTo(30);
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT vod_hits, vod_hits_day, vod_hits_week, vod_hits_month, vod_up,"
                     + " vod_down, vod_score, vod_score_all, vod_score_num FROM mac_vod WHERE vod_id = 1")) {
            rs.next();
            assertThat(rs.getLong(1)).as("总点击：100 次 ajax/hits + 30 次 Provide").isEqualTo(130);
            assertThat(rs.getLong(2)).isEqualTo(100);
            assertThat(rs.getLong(3)).isEqualTo(100);
            assertThat(rs.getLong(4)).isEqualTo(100);
            assertThat(rs.getLong(5)).isEqualTo(60);
            assertThat(rs.getLong(6)).isEqualTo(40);
            assertThat(rs.getString(7)).isEqualTo("5.5");
            assertThat(rs.getLong(8)).isEqualTo(275);
            assertThat(rs.getLong(9)).isEqualTo(50);
        }
        // 网址来路：40 次，两个同前缀的网址各自 +40
        var referer = ConcurrentWrites.run(40, i -> counters.referer("site.example.test").status());
        assertThat(referer.count(CounterOutcome.Status.FOUND)).isEqualTo(40);
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT website_referer, website_referer_day FROM mac_website"
                     + " WHERE website_id IN (1, 2) ORDER BY website_id")) {
            while (rs.next()) {
                assertThat(rs.getLong(1)).isEqualTo(40);
                assertThat(rs.getLong(2)).isEqualTo(40);
            }
        }
        // 评论举报与顶踩：各 50 次
        ConcurrentWrites.run(50, i -> comments.report(900));
        ConcurrentWrites.run(50, i -> comments.digg(900, i % 2 == 0));
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT comment_report, comment_up, comment_down FROM mac_comment"
                     + " WHERE comment_id = 900")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(50);
            assertThat(rs.getLong(2)).isEqualTo(25);
            assertThat(rs.getLong(3)).isEqualTo(25);
        }
        // 评论发表：16 个并发都写入（旧版也没有去重；频率限制靠 cookie）
        var posts = ConcurrentWrites.run(16, i -> {
            Map<String, PhpInput.Value> params = new LinkedHashMap<>();
            params.put("comment_content", new PhpInput.Value(("并发" + i).getBytes(StandardCharsets.UTF_8), false));
            params.put("comment_mid", new PhpInput.Value("1".getBytes(StandardCharsets.UTF_8), false));
            params.put("comment_rid", new PhpInput.Value("2".getBytes(StandardCharsets.UTF_8), false));
            PostReply reply = comments.postComment(comments.settings(), params, Poster.GUEST, false, () -> true,
                    2130706433L);
            return reply.code();
        });
        assertThat(posts.count(1)).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM mac_comment WHERE comment_rid = 2")).isEqualTo(16);
        // 来路访问记录：同一 IP 16 个并发，每日 1 次的限制只放行 1 个（旧版先数后插可以超额）
        MemberReferralService referral = app.getBean(MemberReferralService.class);
        var visits = ConcurrentWrites.run(16, i -> referral.siteVisit("127.0.0.1", "http://site.example.test/x", 1));
        assertThat(visits.count(1)).isEqualTo(1);
        assertThat(visits.count(102)).isEqualTo(15);
        assertThat(count("SELECT COUNT(*) FROM mac_visit")).isEqualTo(1);
        // 搜索缓存：同一个词 16 个并发首次搜索，只插入一行，命中次数 16（旧版重复键报错，计数先读后写）
        VodSearchCache cache = app.getBean(VodSearchCache.class);
        var searches = ConcurrentWrites.run(16, i -> cache.resultIds("星际", VodSearchField.KEYWORD, false).size());
        assertThat(searches.failures()).isEmpty();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(search_hit_count) FROM mac_vod_search")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
            assertThat(rs.getLong(2)).isEqualTo(16);
        }
    }

    // ------------------------------------------------------------------ ④ 回滚演练

    @Test
    @Order(5)
    void dmlRestoreBringsEveryTableBackToTheSnapshot() throws Exception {
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot now = DatabaseSnapshot.take(c, TABLES);
            SnapshotDiff before = SnapshotDiff.between(s0, now);
            assertThat(before.isEmpty()).as("本测试确实改动了数据").isFalse();
            List<DmlRestore.Statement> plan = DmlRestore.plan(s0, now);
            int applied = DmlRestore.apply(c, plan);
            System.out.println("[mysql-it] B11 rollback drill: " + applied + " DML statements; " + before.summary());
            assertThat(SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES)).isEmpty()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 工具：配置、请求、行

    private static void writeConfig(JsonNode base, JsonNode over) throws IOException {
        PhpArray m = PhpConfigFiles.merge(repoConfig, base);
        // Provide 的视频接口打开（B06 读启动时的设置；本测试按请求读，见 setUp）
        m = PhpConfigFiles.merge(m, JSON.readTree("{\"api\":{\"vod\":{\"status\":1}}}"));
        m = PhpConfigFiles.merge(m, over);
        PhpConfigFiles.write(configDir.resolve("extra/maccms.php"), m);
    }

    /** 黄金样本的会员 cookie（interaction_golden.php 的 user_cookie）。 */
    private static String userCookie(long userId) throws Exception {
        String name = userId == 7 ? "member007" : "member008";
        String random = "rnd" + userId;
        byte[] d = MessageDigest.getInstance("MD5").digest((random + "-" + name + "-" + userId + "-")
                .getBytes(StandardCharsets.UTF_8));
        return "user_id=" + userId + "; user_name=" + name + "; user_check=" + HexFormat.of().formatHex(d);
    }

    /** 让会话里的验证码恰好是 {@code code}（码表是仓库的数字码表，按码表下标依次取）。 */
    private static void issueCaptcha(SessionState session, String code, long now) {
        CaptchaSettings settings = app.getBean(CaptchaSettings.class);
        String set = settings.codeSet();
        int[] picks = code.chars().map(ch -> set.indexOf(ch)).toArray();
        RandomGenerator scripted = new RandomGenerator() {
            private int i;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                if (i < picks.length && bound == set.length()) {
                    return picks[i++];
                }
                return 0;
            }
        };
        Captchas.issue(session, Captchas.DEFAULT_SLOT, settings, now, scripted);
    }

    private record Response(int status, String contentType, List<String> setCookies, String body) {
    }

    /** 原始套接字请求：Host 固定为黄金样本的 www.example.test（HttpClient 不允许改 Host）。 */
    private static Response send(String method, String target, Map<String, String> headers, String body)
            throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(30_000);
            StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: www.example.test\r\nConnection: close\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            byte[] payload = body.getBytes(StandardCharsets.ISO_8859_1);
            if (method.equals("POST")) {
                head.append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ").append(payload.length)
                        .append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (method.equals("POST")) {
                out.write(payload);
            }
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            in.transferTo(raw);
            return parse(raw.toByteArray());
        }
    }

    private static Response parse(byte[] raw) {
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int sep = all.indexOf("\r\n\r\n");
        String[] lines = all.substring(0, sep).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        String type = "";
        boolean chunked = false;
        List<String> cookies = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int p = lines[i].indexOf(':');
            String name = lines[i].substring(0, p).trim().toLowerCase();
            String value = lines[i].substring(p + 1).trim();
            switch (name) {
                case "content-type" -> type = value;
                case "set-cookie" -> cookies.add(new String(value.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8));
                case "transfer-encoding" -> chunked = value.equalsIgnoreCase("chunked");
                default -> {
                }
            }
        }
        byte[] body = java.util.Arrays.copyOfRange(raw, sep + 4, raw.length);
        if (chunked) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int i = 0;
            while (i < body.length) {
                int end = i;
                while (body[end] != '\r') {
                    end++;
                }
                int size = Integer.parseInt(new String(body, i, end - i, StandardCharsets.ISO_8859_1).trim(), 16);
                if (size == 0) {
                    break;
                }
                out.write(body, end + 2, size);
                i = end + 2 + size + 2;
            }
            body = out.toByteArray();
        }
        return new Response(status, type, cookies, new String(body, StandardCharsets.UTF_8));
    }

    private static List<String> goldenCookies(JsonNode c) {
        List<String> out = new ArrayList<>();
        c.get("setCookies").forEach(n -> {
            if (!n.asString().startsWith("PHPSESSID=")) {
                out.add(new String(n.asString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
        });
        return out;
    }

    /** Set-Cookie 去掉 expires 日期（两边各按自己的"现在"计算），其余（名、值、Max-Age、path）逐字比较。 */
    private static List<String> normalizeCookies(List<String> cookies) {
        return cookies.stream().filter(x -> !x.startsWith("PHPSESSID="))
                .map(x -> x.replaceAll("; expires=[^;]*", "")).toList();
    }

    private static List<String> cookieNames(List<String> cookies) {
        return normalizeCookies(cookies).stream().map(x -> x.substring(0, x.indexOf('='))).toList();
    }

    /** 当前库里的行（自增主键去掉，"现在"换成 NOW）。 */
    private static Map<String, List<Map<String, String>>> readRows(long from, long to) throws SQLException {
        Map<String, List<Map<String, String>>> out = new TreeMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String t : TABLES) {
                String sql;
                if (PREFIX.containsKey(t)) {
                    String p = PREFIX.get(t);
                    List<String> cols = new ArrayList<>(List.of(p + "_id"));
                    COUNTER_COLUMNS.forEach(x -> cols.add(p + "_" + x));
                    if (p.equals("website")) {
                        for (String x : List.of("referer", "referer_day", "referer_week", "referer_month", "time_referer")) {
                            cols.add(p + "_" + x);
                        }
                    }
                    sql = "SELECT " + String.join(", ", cols) + " FROM " + t + " ORDER BY " + p + "_id";
                } else if (t.equals("mac_user")) {
                    sql = "SELECT user_id, user_name, user_nick_name FROM mac_user ORDER BY user_id";
                } else if (t.equals("mac_vod_search")) {
                    sql = "SELECT * FROM mac_vod_search ORDER BY search_key";
                } else {
                    sql = "SELECT * FROM " + t + " ORDER BY " + AUTO_KEYS.get(t);
                }
                List<Map<String, String>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getString(i));
                        }
                        rows.add(normalizeRow(t, row, from, to));
                    }
                }
                out.put(t, rows);
            }
        }
        return out;
    }

    private static List<Map<String, String>> goldenRows(JsonNode c, String table) {
        long from = c.get("started").asLong();
        long to = c.get("after").asLong();
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode r : c.get("rows").get(table)) {
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : r.properties()) {
                row.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString());
            }
            rows.add(normalizeRow(table, row, from, to));
        }
        return rows;
    }

    /** 用例开始前（执行完黄金的初始语句之后）的行：没有写入时，请求后应与之相同。 */
    private static Map<String, List<Map<String, String>>> setupRows(JsonNode c, long started) {
        try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
            for (JsonNode sql : c.get("setup")) {
                st.execute(sql.asString());
            }
            return readRows(started, started);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> normalizeRow(String table, Map<String, String> row, long from, long to) {
        if (AUTO_KEYS.containsKey(table)) {
            row.remove(AUTO_KEYS.get(table));
        }
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (NOW_COLUMNS.contains(e.getKey()) && e.getValue() != null) {
                long v = Long.parseLong(e.getValue());
                if (v >= from && v <= to) {
                    e.setValue("NOW");
                }
            }
        }
        return row;
    }

    private static List<Map<String, String>> searchRows(long now) throws SQLException {
        return readRows(now, now).get("mac_vod_search");
    }

    private static List<Map<String, String>> goldenSearchRows(JsonNode s) {
        long from = s.get("started").asLong();
        long to = s.get("after").asLong();
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode r : s.get("rows")) {
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : r.properties()) {
                row.put(e.getKey(), e.getValue().asString());
            }
            // D-B11-9：未命中时新版最多存 1001 个 ID；超过 1000 个的结果旧版与新版都不使用
            String ids = row.get("search_result_ids");
            String[] parts = ids.isEmpty() ? new String[0] : ids.split(",");
            if (parts.length > 1001 && !s.get("setup").toString().contains(ids.substring(0, Math.min(ids.length(), 40)))) {
                row.put("search_result_ids", String.join(",", java.util.Arrays.copyOf(parts, 1001)));
                row.put("search_result_count", "1001");
            }
            rows.add(normalizeRow("mac_vod_search", row, from, to));
        }
        return rows;
    }

    private static String text(JsonNode p, String key) {
        if (!p.has(key)) {
            return null;
        }
        String v = p.get(key).asString().strip();
        return v.isEmpty() ? null : v;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ 工具：库

    private static JsonNode load(String resource) {
        try (InputStream in = InteractionGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    private static Connection admin(String db) throws SQLException {
        String url = db == null ? adminUrl : jdbcUrlFor(db);
        return DriverManager.getConnection(url, adminUser, adminPassword);
    }

    private static String jdbcUrlFor(String db) {
        String base = adminUrl;
        int q = base.indexOf('?');
        String query = q < 0 ? "" : base.substring(q);
        String head = q < 0 ? base : base.substring(0, q);
        if (!head.endsWith("/")) {
            head = head.substring(0, head.lastIndexOf('/') + 1);
        }
        return head + db + query;
    }

    private static List<String> statements(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                kept.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : kept.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.strip());
            }
        }
        return out;
    }

    /** 测试固定的时钟（秒）。 */
    static final class MutableClock extends Clock {
        private final AtomicLong epoch = new AtomicLong();

        void set(long seconds) {
            epoch.set(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochSecond(epoch.get());
        }
    }
}
