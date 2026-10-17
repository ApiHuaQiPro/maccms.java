package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.openapi.application.ProvideSettings;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B06 Provide 资源站接口的 PHP 黄金对照（./mvnw verify -Pmysql-it；docs/architecture/16-b06-misc-pages-provide.md 第 3、6、7 节）。
 *
 * <p>新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构与合成数据 openapi/provide/seed.sql（与
 * backend/src/test/php/provide_golden.php 相同）→ 以真实 HTTP 启动应用（openapi 打开，可信代理 127.0.0.1：用例中旧版的
 * REMOTE_ADDR 以 X-Forwarded-For 交给新版，伪造的 CF-Connecting-IP/X-Real-IP 照原样发送）→ 每个用例按黄金样本的旧配置覆盖重放
 * 旧配置（{@link ProvideSettingsSource}）、按记录的执行时刻固定时钟（h 参数）→ 请求同一地址：除登记差异外，状态码对应、正文与
 * PHP 8.0 输出逐字节相同；登记差异逐条断言新版行为 → 主用例之后导入 phase-diff.sql 断言不可见分类、访问密码、XML 特殊字符等差异
 * → 表结构不变 → 删除该一次性库。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
class ProvideGoldenMySqlIT {

    private static final java.util.regex.Pattern DB_NAME = java.util.regex.Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path LEGACY_MACCMS = Path.of("..", "application", "extra", "maccms.php");
    private static final Path SEED = Path.of("src", "test", "resources", "openapi", "provide", "seed.sql");
    private static final Path PHASE_DIFF = Path.of("src", "test", "resources", "openapi", "provide", "phase-diff.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** 与 provide_golden.php 相同：h 用例执行后恢复的种子时间。 */
    private static final Map<String, Map<Integer, Long>> SEED_TIMES = Map.of(
            "vod", Map.of(1, 1700001000L, 2, 1700002000L, 3, 1700003000L),
            "art", Map.of(1, 1700001100L, 2, 1700001200L, 3, 1700009100L),
            "actor", Map.of(1, 1700001300L, 2, 1700009200L),
            "role", Map.of(1, 1700001400L, 2, 1700001500L),
            "website", Map.of(1, 1700001600L));

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static JsonNode golden;
    private static PhpArray legacyBase;
    private static final AtomicReference<ProvideSettings> SETTINGS = new AtomicReference<>(ProvideSettings.closed());
    private static final MutableClock CLOCK = new MutableClock();
    private static ConfigurableApplicationContext app;
    private static String baseUrl;

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant i) {
            now.set(i);
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
            return now.get();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        try (InputStream in = ProvideGoldenMySqlIT.class.getResourceAsStream("/openapi/provide/php80-golden.json")) {
            golden = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        legacyBase = VarExportParser.parse(Files.readAllBytes(LEGACY_MACCMS));
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
                "--maccms.openapi.enabled=true",
                "--maccms.client-ip.trusted-proxies=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .initializers(ctx -> {
                    GenericApplicationContext g = (GenericApplicationContext) ctx;
                    g.registerBean("goldenProvideSettings", ProvideSettingsSource.class, () -> SETTINGS::get);
                    g.registerBean("goldenClock", Clock.class, () -> CLOCK);
                })
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

    record Reply(int status, String contentType, String cacheControl, String nosniff, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyGoldenCaseMatchesTheLegacyOutputOrTheRegisteredDifference() throws Exception {
        assertThat(golden.get("php").asString()).startsWith("8.0");
        Map<String, byte[]> phpBodies = new LinkedHashMap<>();
        for (JsonNode c : golden.get("cases")) {
            phpBodies.put(c.get("id").asString(), Base64.getDecoder().decode(c.get("body").asString()));
        }
        int exact = 0;
        int differences = 0;
        boolean diffLoaded = false;
        List<String> failures = new ArrayList<>();
        for (JsonNode c : golden.get("cases")) {
            String id = c.get("id").asString();
            JsonNode req = c.get("request");
            if (!req.get("entry").asString().equals("api")) {
                continue; // index 入口的 ajax/qrcode 用例由 site 的 AjaxGoldenMySqlIT 对照
            }
            if (c.get("phase").asString().equals("diff") && !diffLoaded) {
                try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
                    for (String sql : statements(PHASE_DIFF)) {
                        st.execute(sql);
                    }
                }
                diffLoaded = true;
            }
            SETTINGS.set(ProvideSettings.from(merge(legacyBase, c.get("config"))));
            long now = c.get("now").asLong();
            CLOCK.set(Instant.ofEpochSecond(now));
            applyRelativeTimes(c.get("rel"), now);
            if (id.equals("vod.cache.second")) {
                exec("UPDATE mac_vod SET vod_name='改名后' WHERE vod_id=2");
            }
            Reply r;
            try {
                r = request(req);
            } finally {
                if (c.get("rel").size() > 0) {
                    restoreSeedTimes();
                }
                if (id.equals("vod.cache.second")) {
                    exec("UPDATE mac_vod SET vod_name='欢乐喜剧' WHERE vod_id=2");
                }
            }
            try {
                if (check(id, c, r, phpBodies)) {
                    exact++;
                } else {
                    differences++;
                }
            } catch (AssertionError e) {
                String msg = String.valueOf(e.getMessage());
                failures.add(id + ": " + msg.substring(0, Math.min(msg.length(), 600)));
            }
        }
        System.out.println("[mysql-it] provide golden: " + exact + " exact, " + differences + " registered differences");
        assertThat(failures).as("不一致的用例").isEmpty();
        assertThat(exact).as("逐字节相同的用例数").isGreaterThanOrEqualTo(90);
    }

    /**
     * LegacyTable 的整行列清单（与 LegacyRowMapper.xml 由同一份清单生成）与一次性库的 information_schema 一致：旧表的全部列按顺序，
     * 只去掉访问密码列。
     */
    @Test
    void fullColumnListsMatchInformationSchema() throws Exception {
        Set<String> passwords = Set.of("vod_pwd", "vod_pwd_url", "vod_pwd_play", "vod_pwd_play_url", "vod_pwd_down",
                "vod_pwd_down_url", "art_pwd", "art_pwd_url");
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (com.maccms.backend.content.application.LegacyTable t
                    : com.maccms.backend.content.application.LegacyTable.values()) {
                List<String> cols = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = '"
                        + dbName + "' AND table_name = 'mac_" + t.prefix() + "' ORDER BY ordinal_position")) {
                    while (rs.next()) {
                        if (!passwords.contains(rs.getString(1))) {
                            cols.add(rs.getString(1));
                        }
                    }
                }
                assertThat(t.fullColumns()).as(t.name()).isEqualTo(cols);
            }
        }
    }

    /** 返回 true 表示与旧版逐字节相同，false 表示按登记差异断言通过。 */
    private static boolean check(String id, JsonNode c, Reply r, Map<String, byte[]> phpBodies) throws Exception {
        {
            byte[] php = phpBodies.get(id);
            int phpStatus = c.get("status").asInt();
            assertThat(r.nosniff()).as(id + " nosniff").isEqualTo("nosniff");
            if (c.get("legacyOnly").asBoolean() || REGISTERED.contains(id)) {
                registeredDifference(id, c, r, php, phpBodies);
                return false;
            }
            String phpText = new String(php, StandardCharsets.UTF_8);
            if (phpStatus == 500) {
                // 旧版 SQL/类型错误 → 新版 400（不回显）
                assertThat(r.status()).as(id).isEqualTo(400);
                assertThat(r.text()).as(id).isEqualTo("参数错误");
                return false;
            }
            if (phpText.equals("域名未授权")) {
                assertThat(r.status()).as(id + "（白名单拒绝 403，D-B06-5）").isEqualTo(403);
                assertThat(mime(r.contentType())).isEqualTo("text/plain;charset=utf-8");
            } else {
                assertThat(r.status()).as(id).isEqualTo(phpStatus);
            }
            // 登记差异的规范化：① 整行不输出访问密码列（D-B06-3；主数据中这些列都是空串）；② 旧 Art/Actor/Website/Role::listData
            // 的 total 不带 datafilter（Art.php:50-53 等，未审核行也计入），新版 total 与列表同一条件，pagecount 随之变化
            String expected = PWD_EMPTY.matcher(phpText).replaceAll("");
            boolean registered = !expected.equals(phpText);
            if (phpText.startsWith("{") && LEGACY_TOTAL_IGNORES_DATAFILTER.contains(id.substring(0, id.indexOf('.')))
                    && r.text().startsWith("{")) {
                JsonNode o = JSON.readTree(phpText);
                JsonNode n = JSON.readTree(r.text());
                if (o.get("total").asLong() != n.get("total").asLong()) {
                    assertThat(n.get("total").asLong()).as(id + " total").isLessThan(o.get("total").asLong());
                    long limit = Long.parseLong(n.get("limit").isString() ? n.get("limit").asString()
                            : Long.toString(n.get("limit").asLong()));
                    assertThat(n.get("pagecount").asLong()).isEqualTo((n.get("total").asLong() + limit - 1) / limit);
                    expected = expected.replace("\"pagecount\":" + o.get("pagecount").asLong() + ",",
                                    "\"pagecount\":" + n.get("pagecount").asLong() + ",")
                            .replace("\"total\":" + o.get("total").asLong() + ",", "\"total\":" + n.get("total").asLong() + ",");
                    registered = true;
                }
            }
            if (!r.text().equals(expected)) {
                int i = 0;
                String a = expected;
                String b = r.text();
                while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
                    i++;
                }
                fail(id + " 第 " + i + " 个字符起不同：旧 " + a.substring(Math.max(0, i - 120), Math.min(a.length(), i + 120))
                        + " ｜新 " + b.substring(Math.max(0, i - 120), Math.min(b.length(), i + 120)));
            }
            if (registered) {
                return false;
            }
            assertThat(r.body()).as(id).isEqualTo(php);
            String expectedType = phpText.isEmpty() || phpText.equals("closed") || phpText.equals("域名未授权")
                    ? "text/plain;charset=utf-8"
                    : phpText.startsWith("<?xml") ? "text/xml;charset=utf-8" : "application/json;charset=utf-8";
            assertThat(mime(r.contentType())).as(id + " MIME（D-B06-1）").isEqualTo(expectedType);
            assertThat(r.cacheControl()).as(id).isEqualTo("no-store");
            return true;
        }
    }

    private static String mime(String contentType) {
        return contentType.replace("; ", ";").toLowerCase(java.util.Locale.ROOT);
    }

    private static final Set<String> LEGACY_TOTAL_IGNORES_DATAFILTER = Set.of("art", "actor", "website", "role");
    private static final java.util.regex.Pattern PWD_EMPTY = java.util.regex.Pattern.compile(
            "\"(?:vod_pwd|vod_pwd_url|vod_pwd_play|vod_pwd_play_url|vod_pwd_down|vod_pwd_down_url|art_pwd|art_pwd_url)\":\"\",");

    /** 主用例中按登记差异断言的用例（其余为 legacyOnly）。 */
    private static final Set<String> REGISTERED = Set.of("vod.wd-percent", "vod.cache.first");

    private static void registeredDifference(String id, JsonNode c, Reply r, byte[] php, Map<String, byte[]> phpBodies)
            throws Exception {
        String phpText = new String(php, StandardCharsets.UTF_8);
        switch (id) {
            case "vod.wd-percent" -> {
                // 旧版 LIKE 不转义：wd=% 命中全部；新版按字面值，只有片名含 % 的 7
                assertThat(ids(r, "vod_id")).isEqualTo(List.of(7L));
                assertThat(ids(phpText, "vod_id")).hasSize(9);
            }
            case "vod.cache.first" -> {
                // cachetime=60：正文与旧版相同，缓存改为 HTTP 头（D-B06-4）
                assertThat(r.text()).isEqualTo(phpText);
                assertThat(r.cacheControl()).isEqualTo("public, max-age=60");
            }
            case "vod.cache.second" -> {
                // 旧版第二次返回缓存（旧名）；新版不做服务端缓存，返回改名后的数据
                assertThat(phpText).contains("欢乐喜剧").doesNotContain("改名后");
                assertThat(r.text()).contains("改名后");
                assertThat(r.cacheControl()).isEqualTo("public, max-age=60");
            }
            case "vod.charge.spoof-cf", "vod.charge.spoof-xreal" -> {
                // 旧版信任 CF-Connecting-IP / X-Real-IP，伪造即通过白名单；新版只认可信代理转发的 X-Forwarded-For
                assertThat(phpText).startsWith("{\"code\":1");
                assertThat(r.status()).isEqualTo(403);
                assertThat(r.text()).isEqualTo("域名未授权");
            }
            case "vod.datafilter-empty" -> {
                // 旧版 datafilter 为空时输出未审核的 5；新版始终只含已审核（D-B06-2）
                assertThat(ids(phpText, "vod_id")).contains(5L);
                assertThat(r.text()).isEqualTo(new String(phpBodies.get("vod.list"), StandardCharsets.UTF_8));
            }
            case "diff.vod.list" -> {
                List<Long> old = ids(phpText, "vod_id");
                assertThat(old).contains(30L, 31L, 32L);
                List<Long> expected = new ArrayList<>(old);
                expected.removeAll(List.of(30L, 31L, 32L));
                assertThat(ids(r, "vod_id")).isEqualTo(expected);
                JsonNode n = JSON.readTree(r.text());
                assertThat(n.get("total").asLong()).isEqualTo(JSON.readTree(phpText).get("total").asLong() - 3);
                List<Long> classIds = new ArrayList<>();
                for (JsonNode t : n.get("class")) {
                    classIds.add(t.get("type_id").asLong());
                }
                assertThat(classIds).contains(7L).doesNotContain(5L, 6L);
            }
            case "diff.vod.list.xml" -> {
                assertThat(wellFormed(php)).as("旧版 XML 含未转义的 & 与 <，不是合法 XML").isFalse();
                Document d = parse(r.body());
                assertThat(text(d, "name")).contains("特殊]]>字符<名>").doesNotContain("会员分类视频");
                assertThat(text(d, "type")).contains("R&B<片>");
                assertThat(text(d, "ty")).contains("R&B<片>").doesNotContain("停用分类", "会员分类");
            }
            case "diff.vod.detail" -> {
                JsonNode n = JSON.readTree(r.text());
                Map<Long, JsonNode> rows = rowsById(n, "vod_id");
                assertThat(rows.keySet()).containsExactly(36L, 35L, 34L, 33L);
                for (JsonNode row : n.get("list")) {
                    for (String col : List.of("vod_pwd", "vod_pwd_url", "vod_pwd_play", "vod_pwd_play_url", "vod_pwd_down",
                            "vod_pwd_down_url")) {
                        assertThat(row.has(col)).as("不输出密码列 " + col).isFalse();
                    }
                }
                assertThat(rows.get(33L).get("vod_play_url").asString()).isEmpty();
                assertThat(rows.get(33L).get("vod_down_url").asString()).isNotEmpty();
                assertThat(rows.get(34L).get("vod_content").asString()).isEmpty();
                assertThat(rows.get(34L).get("vod_play_url").asString()).isEmpty();
                assertThat(rows.get(34L).get("vod_down_url").asString()).isEmpty();
                assertThat(rows.get(35L).get("vod_down_url").asString()).isEmpty();
                assertThat(rows.get(35L).get("vod_play_url").asString()).isNotEmpty();
                // 其余字段与旧版相同
                Map<Long, JsonNode> old = rowsById(JSON.readTree(phpText), "vod_id");
                assertThat(old.keySet()).contains(30L, 31L, 32L);
                for (long vid : rows.keySet()) {
                    assertSameExcept(old.get(vid), rows.get(vid), Set.of("vod_pwd", "vod_pwd_url", "vod_pwd_play",
                            "vod_pwd_play_url", "vod_pwd_down", "vod_pwd_down_url", "vod_content", "vod_play_url",
                            "vod_down_url"));
                }
                assertThat(rows.get(36L).get("vod_content").asString()).isEqualTo(old.get(36L).get("vod_content").asString());
            }
            case "diff.vod.detail.xml" -> {
                Document d = parse(r.body());
                NodeList videos = d.getElementsByTagName("video");
                assertThat(videos.getLength()).isEqualTo(4);
                for (int i = 0; i < videos.getLength(); i++) {
                    Element v = (Element) videos.item(i);
                    String vid = v.getElementsByTagName("id").item(0).getTextContent();
                    String dd = v.getElementsByTagName("dd").item(0).getTextContent();
                    String des = v.getElementsByTagName("des").item(0).getTextContent();
                    switch (vid) {
                        case "33" -> assertThat(dd).isEmpty();
                        case "34" -> assertThat(dd + des).isEmpty();
                        case "36" -> {
                            assertThat(des).isEqualTo("<p>]]> 正文</p>");
                            assertThat(((Element) v.getElementsByTagName("dd").item(0)).getAttribute("flag")).isEqualTo("a&b");
                        }
                        default -> assertThat(dd).isNotEmpty();
                    }
                }
            }
            case "diff.art.detail" -> {
                JsonNode row = JSON.readTree(r.text()).get("list").get(0);
                assertThat(row.get("art_content").asString()).isEmpty();
                assertThat(row.has("art_pwd")).isFalse();
                assertThat(row.has("art_pwd_url")).isFalse();
                assertThat(JSON.readTree(phpText).get("list").get(0).get("art_pwd").asString()).isEqualTo("p4");
            }
            case "diff.role.list", "diff.role.detail" -> {
                // 旧版：角色的 datafilter 被忽略（未审核的 4 也输出），关联视频不看可见性（3 的 vod_name 为会员分类视频）
                assertThat(ids(phpText, "role_id")).contains(3L, 4L);
                JsonNode n = JSON.readTree(r.text());
                Map<Long, JsonNode> rows = rowsById(n, "role_id");
                assertThat(rows).doesNotContainKey(4L).containsKey(3L);
                assertThat(rows.get(3L).get("vod_name").isNull()).isTrue();
                assertThat(rows.get(3L).get("douban_id").isNull()).isTrue();
                assertThat(rows.get(3L).get("vod_director").isNull()).isTrue();
                if (id.equals("diff.role.list")) {
                    assertThat(rows.get(1L).get("vod_name").asString()).isEqualTo("星际穿越");
                }
            }
            default -> fail("未登记的差异用例 " + id);
        }
    }

    private static void assertSameExcept(JsonNode old, JsonNode now, Set<String> except) {
        List<String> oldKeys = new ArrayList<>();
        old.propertyNames().forEach(k -> {
            if (!except.contains(k)) {
                oldKeys.add(k);
            }
        });
        List<String> newKeys = new ArrayList<>();
        now.propertyNames().forEach(k -> {
            if (!except.contains(k)) {
                newKeys.add(k);
            }
        });
        assertThat(newKeys).isEqualTo(oldKeys);
        for (String k : oldKeys) {
            assertThat(now.get(k)).as(k).isEqualTo(old.get(k));
        }
    }

    private static Map<Long, JsonNode> rowsById(JsonNode n, String key) {
        Map<Long, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode row : n.get("list")) {
            m.put(row.get(key).asLong(), row);
        }
        return m;
    }

    private static List<Long> ids(Reply r, String key) throws Exception {
        assertThat(r.status()).isEqualTo(200);
        return ids(r.text(), key);
    }

    private static List<Long> ids(String json, String key) {
        List<Long> out = new ArrayList<>();
        for (JsonNode row : JSON.readTree(json).get("list")) {
            out.add(row.get(key).asLong());
        }
        return out;
    }

    private static boolean wellFormed(byte[] xml) {
        try {
            parse(xml);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        var b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b.parse(new ByteArrayInputStream(xml));
    }

    private static String text(Document d, String tag) {
        StringBuilder sb = new StringBuilder();
        NodeList l = d.getElementsByTagName(tag);
        for (int i = 0; i < l.getLength(); i++) {
            sb.append(l.item(i).getTextContent()).append('|');
        }
        return sb.toString();
    }

    private static Reply request(JsonNode req) throws IOException, InterruptedException {
        String query = req.get("query").asString();
        String uri = baseUrl + "/api.php" + req.get("path").asString() + (query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).GET();
        if (req.has("headers")) {
            for (var e : req.get("headers").properties()) {
                b.header(e.getKey(), e.getValue().asString());
            }
        }
        if (req.has("remote") && !req.get("remote").asString().equals("127.0.0.1")) {
            // 旧版的 REMOTE_ADDR：经可信代理 127.0.0.1 以 X-Forwarded-For 交给新版
            b.header("X-Forwarded-For", req.get("remote").asString());
        }
        HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(""),
                resp.headers().firstValue("Cache-Control").orElse(""),
                resp.headers().firstValue("X-Content-Type-Options").orElse(""), resp.body());
    }

    /** 把黄金样本的旧配置覆盖并入仓库的 extra/maccms.php（覆盖值都是字符串，与 provide_golden.php 的 deep_merge 相同）。 */
    static PhpArray merge(PhpArray base, JsonNode over) {
        Map<Object, PhpValue> m = new LinkedHashMap<>(base.entries());
        for (var e : over.properties()) {
            Object key = PhpArray.normalizeKey(e.getKey());
            PhpValue cur = m.get(key);
            if (e.getValue().isObject()) {
                m.put(key, merge(cur instanceof PhpArray a ? a : new PhpArray(Map.of()), e.getValue()));
            } else {
                m.put(key, new PhpValue.PhpString(e.getValue().asString()));
            }
        }
        return new PhpArray(m);
    }

    private static void applyRelativeTimes(JsonNode rel, long now) throws SQLException {
        for (var e : rel.properties()) {
            long ago = e.getValue().asLong();
            int id = Integer.parseInt(e.getKey());
            for (String t : List.of("vod", "art", "actor", "role", "website")) {
                exec("UPDATE mac_" + t + " SET " + t + "_time = " + (now - ago) + " WHERE " + t + "_id = " + id);
            }
        }
    }

    private static void restoreSeedTimes() throws SQLException {
        for (var t : SEED_TIMES.entrySet()) {
            for (var row : t.getValue().entrySet()) {
                exec("UPDATE mac_" + t.getKey() + " SET " + t.getKey() + "_time = " + row.getValue() + " WHERE "
                        + t.getKey() + "_id = " + row.getKey());
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute(sql);
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
