package com.maccms.backend.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B19 采集入库与 Receive 入站接口的影子对比（./mvnw verify -Pmysql-it；docs/architecture/27-b19-collect-ingest.md 第 7、8 节）。
 *
 * <ol>
 * <li>影子合并对比：新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 与 3 个分类（同 receive_golden.php）→ 以真实 HTTP 启动应用
 * （可写数据源打开）→ 每个场景按黄金样本写入旧配置 maccms.php、清空 mac_vod 与 mac_vod_search 并复位自增、写入同样的种子行 → 逐步发送与旧版
 * 相同的请求：状态码、Content-Type、正文与 PHP 8.0、7.3 的输出逐字节相同，请求后两张表逐行逐列相同（写入时刻的列按两边各自的请求时间窗比较，
 * 随机列按旧版的取值范围比较）；登记差异逐条断言新版行为。</li>
 * <li>入库排队：16 个并发请求提交同一部新视频，只新增一行，其余按更新规则处理。</li>
 * <li>回滚演练：快照 → 一批新增与更新 → {@code DmlRestore} 只用 DML 恢复 → 逐行 diff 为空 → 表结构不变。</li>
 * </ol>
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev），不访问网络。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReceiveGoldenMySqlIT {

    static final String PHP80 = "collect/receive/php80-golden.json";
    static final String PHP73 = "collect/receive/php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> TABLES = List.of("mac_vod", "mac_vod_search");
    private static final Map<String, String> PK = Map.of("mac_vod", "vod_id", "mac_vod_search", "search_key");
    /** 写入时刻的列：旧值落在旧版请求的时间窗里时，新值须落在新版请求的时间窗里；否则逐字相同。 */
    private static final Set<String> TIME_COLUMNS = Set.of("vod_time", "vod_time_add", "search_last_hit_time", "search_update_time");
    /** 随机列（随机场景）：旧版 rand 的取值范围。 */
    private static final Set<String> RANDOM_COLUMNS = Set.of("vod_hits", "vod_hits_day", "vod_hits_week", "vod_hits_month", "vod_up",
            "vod_down", "vod_score", "vod_score_all", "vod_score_num");

    /** 有意差异（27 文档第 10 节）：键为 场景/步骤，值为新版的状态码与正文。 */
    static final Map<String, String> JAVA = Map.of(
            // D-B19-3：旧版缺 vod_content 时 MySQL 严格模式报错、整页 500；新版不写库，返回"参数错误"
            "require/no-content", "200 text/html; charset=utf-8 {\"code\":1001,\"msg\":\"参数错误\"}");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static Path legacy;
    private static Path maccmsFile;
    private static int port;
    private static long mtime = Instant.now().getEpochSecond();

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
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String sql : installStatements()) {
                    st.execute(sql);
                }
                // 与 receive_golden.php 相同的分类
                st.execute("SET SESSION sql_mode = ''");
                st.execute("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
                        + "(1, '电影', 'dianying', 1, 1, 0, 1), (6, '动作片', 'dongzuopian', 2, 1, 1, 1), "
                        + "(7, '喜剧片', 'xijupian', 3, 1, 1, 1)");
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        Path dir = Files.createTempDirectory("maccms-b19-legacy");
        legacy = dir.resolve("application");
        Path extra = Files.createDirectories(legacy.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        maccmsFile = extra.resolve("maccms.php");
        writeMaccms(load(PHP80).get("scenarios").get(0));
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=/b19admin.php",
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + legacy,
                "--maccms.upload.dir=" + dir.resolve("upload"), "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .run(args.toArray(String[]::new));
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
        if (legacy != null) {
            deleteTree(legacy.getParent());
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            Map<String, String> schemaNow;
            try (Connection c = admin(dbName)) {
                schemaNow = SchemaFingerprint.of(c, dbName);
            }
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '"
                        + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
        }
    }

    // ------------------------------------------------------------------ ① 影子合并对比

    @Test
    @Order(1)
    void scenariosMatchPhpGolden() throws Exception {
        JsonNode g80 = load(PHP80);
        JsonNode g73 = load(PHP73);
        assertThat(g80.get("php").asString()).startsWith("8.0.");
        assertThat(g73.get("php").asString()).startsWith("7.3.");
        List<String> problems = new ArrayList<>();
        int steps = 0;
        for (int i = 0; i < g80.get("scenarios").size(); i++) {
            JsonNode s80 = g80.get("scenarios").get(i);
            JsonNode s73 = g73.get("scenarios").get(i);
            String label = s80.get("label").asString();
            assertThat(s73.get("label").asString()).isEqualTo(label);
            writeMaccms(s80);
            reset(s80.get("seed"));
            for (int j = 0; j < s80.get("steps").size(); j++) {
                JsonNode st80 = s80.get("steps").get(j);
                JsonNode st73 = s73.get("steps").get(j);
                String key = label + "/" + st80.get("label").asString();
                long started = Instant.now().getEpochSecond();
                HttpResponse<byte[]> r = send(st80);
                long after = Instant.now().getEpochSecond();
                String actual = r.statusCode() + " " + r.headers().firstValue("Content-Type").orElse("") + " "
                        + new String(r.body(), StandardCharsets.UTF_8);
                Map<String, List<Map<String, String>>> tables = tables();
                steps++;
                for (JsonNode st : List.of(st80, st73)) {
                    String v = st == st80 ? "80" : "73";
                    String expected = JAVA.containsKey(key) ? JAVA.get(key) : st.get("status").asInt() + " "
                            + st.get("contentType").asString() + " " + new String(Base64.getDecoder().decode(st.get("response").asString()),
                            StandardCharsets.UTF_8);
                    // Content-Type 的参数前有没有空格不影响语义（Tomcat 输出时统一去掉；旧版提前 exit 时也没有空格）
                    if (!expected.replace("; charset", ";charset").equals(actual.replace("; charset", ";charset"))) {
                        problems.add(v + ":" + key + " 响应\n  旧 " + expected + "\n  新 " + actual);
                    }
                    compareTables(v + ":" + key, st, st73, tables, started, after, s80.get("random").asBoolean(), problems);
                }
            }
        }
        assertThat(problems).as("与旧版不同的地方").isEmpty();
        assertThat(steps).isGreaterThanOrEqualTo(40);
    }

    /**
     * 两张表逐行逐列比较。PHP 8.0 的 {@code mac_substring} 把 ASCII 字符换成空格（D-B13-5，新版按 7.3），所以 8.0 的 vod_blurb 与新版不同时
     * 只接受与 7.3 相同的值。
     */
    private static void compareTables(String key, JsonNode st, JsonNode st73, Map<String, List<Map<String, String>>> actual,
                                      long started, long after, boolean random, List<String> problems) {
        for (String t : TABLES) {
            JsonNode rows = st.get("tables").get(t);
            List<Map<String, String>> mine = actual.get(t);
            if (rows.size() != mine.size()) {
                problems.add(key + " " + t + " 行数 旧 " + rows.size() + " 新 " + mine.size());
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                JsonNode old = rows.get(i);
                Map<String, String> row = mine.get(i);
                for (Map.Entry<String, JsonNode> e : old.properties()) {
                    String col = e.getKey();
                    String o = e.getValue().isNull() ? null : e.getValue().asString();
                    String n = row.get(col);
                    if (o == null ? n == null : o.equals(n)) {
                        continue;
                    }
                    if (TIME_COLUMNS.contains(col) && o != null && n != null) {
                        long ov = Long.parseLong(text(o));
                        long nv = Long.parseLong(text(n));
                        boolean oldNow = ov >= st.get("started").asLong() && ov <= st.get("after").asLong() + 1;
                        if (oldNow && nv >= started && nv <= after + 1) {
                            continue;
                        }
                        // 前面步骤写入的时刻：两边各自的"当时"，按相对位置比较（都不早于场景开始的种子时间）
                        if (ov > 1700000100L && nv > 1700000100L) {
                            continue;
                        }
                    }
                    if (random && RANDOM_COLUMNS.contains(col) && inRange(col, text(n), row)) {
                        continue;
                    }
                    if (key.startsWith("80:") && col.equals("vod_blurb")) {
                        JsonNode r73 = st73.get("tables").get(t).get(i).get(col);
                        if (!r73.isNull() && r73.asString().equals(n)) {
                            continue;
                        }
                    }
                    problems.add(key + " " + t + "[" + i + "]." + col + " 旧 " + (o == null ? null : text(o)) + " 新 "
                            + (n == null ? null : text(n)));
                }
                if (row.size() != old.size()) {
                    problems.add(key + " " + t + "[" + i + "] 列数不同");
                }
            }
        }
    }

    /** 随机列：点击与顶踩 1～1000；评分人数 1～1000、总分 = 人数 × (1～10)、评分 = 总分 / 人数。 */
    private static boolean inRange(String col, String v, Map<String, String> row) {
        double x = Double.parseDouble(v);
        long num = Long.parseLong(text(row.get("vod_score_num")));
        long all = Long.parseLong(text(row.get("vod_score_all")));
        return switch (col) {
            case "vod_score" -> all % num == 0 && x == all / num && x >= 1 && x <= 10;
            case "vod_score_all" -> all % num == 0 && all / num >= 1 && all / num <= 10;
            default -> x >= 1 && x <= 1000;
        };
    }

    // ------------------------------------------------------------------ ② 入库排队

    @Test
    @Order(2)
    void concurrentSubmissionsOfOneVideoInsertOnce() throws Exception {
        writeMaccms(scenario("update-play"));
        reset(JSON.readTree("{}"));
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String url = "第" + i + "集$http://v.example.test/" + i + ".m3u8";
                results.add(pool.submit(() -> {
                    go.await();
                    return new String(post(form(Map.of("pass", passOf(), "vod_name", "并发剧", "type_id", "6", "vod_actor", "甲",
                            "vod_director", "乙", "vod_content", "正文", "vod_play_from", "dplayer", "vod_play_url", url))).body(),
                            StandardCharsets.UTF_8);
                }));
            }
            go.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> f : results) {
                bodies.add(f.get());
            }
            assertThat(bodies.stream().filter(b -> b.contains("新加入库")).count()).as("只新增一次").isEqualTo(1);
            assertThat(bodies.stream().filter(b -> b.contains("播放组(dplayer)，更新ok。")).count()).as("其余按更新规则替换播放组")
                    .isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, String>> vods = tables().get("mac_vod");
        assertThat(vods).hasSize(1);
        assertThat(text(vods.get(0).get("vod_name"))).isEqualTo("并发剧");
    }

    // ------------------------------------------------------------------ ③ 回滚演练

    @Test
    @Order(3)
    void rollbackDrillRestoresWithDmlOnly() throws Exception {
        JsonNode s = scenario("update-fields");
        writeMaccms(s);
        reset(s.get("seed"));
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, TABLES);
        }
        for (JsonNode st : s.get("steps")) {
            assertThat(send(st).statusCode()).isEqualTo(200);
        }
        assertThat(post(form(Map.of("pass", passOf(), "vod_name", "回滚新增", "type_id", "6", "vod_content", "c"))).statusCode())
                .isEqualTo(200);
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, TABLES);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, changed);
            assertThat(plan).isNotEmpty();
            DmlRestore.apply(c, plan);
            assertThat(SnapshotDiff.between(before, DatabaseSnapshot.take(c, TABLES)).isEmpty()).as("恢复后与快照相同").isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 工具

    private static JsonNode scenario(String label) throws IOException {
        for (JsonNode s : load(PHP80).get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException(label);
    }

    private static String passOf() throws IOException {
        return load(PHP80).get("pass").asString();
    }

    private static HttpResponse<byte[]> send(JsonNode st) throws IOException, InterruptedException {
        String query = st.get("query").asString();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api.php" + st.get("path").asString() + (query.isEmpty() ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        if (st.get("method").asString().equals("POST")) {
            b.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(st.get("body").asString(), StandardCharsets.ISO_8859_1));
        } else {
            b.GET();
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> post(String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api.php/receive/vod/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String form(Map<String, String> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }

    /** 写入场景的 maccms.php，并把修改时间往后拨（配置按修改时间与大小判断是否重读）。 */
    private static void writeMaccms(JsonNode scenario) throws IOException {
        Files.write(maccmsFile, Base64.getDecoder().decode(scenario.get("maccmsPhp").asString()));
        Files.setLastModifiedTime(maccmsFile, FileTime.from(Instant.ofEpochSecond(++mtime)));
    }

    /** 与 receive_golden.php 的 reset_tables 相同：清空、自增复位、sql_mode 为空时写种子。 */
    private static void reset(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("SET SESSION sql_mode = ''");
            for (String t : TABLES) {
                st.execute("DELETE FROM `" + t + "`");
                if (!t.equals("mac_vod_search")) {
                    st.execute("ALTER TABLE `" + t + "` AUTO_INCREMENT = 1");
                }
            }
            for (Map.Entry<String, JsonNode> e : seed.properties()) {
                for (JsonNode row : e.getValue()) {
                    List<String> cols = new ArrayList<>();
                    row.properties().forEach(x -> cols.add(x.getKey()));
                    String sql = "INSERT INTO `" + e.getKey() + "` (`" + String.join("`, `", cols) + "`) VALUES ("
                            + String.join(", ", cols.stream().map(x -> "?").toList()) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        int i = 1;
                        for (String col : cols) {
                            ps.setString(i++, row.get(col).asString());
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    /** 两张表的全部行（按主键排序；值为 UTF-8 字节的十六进制，同黄金样本）。 */
    private static Map<String, List<Map<String, String>>> tables() throws SQLException {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String t : TABLES) {
                List<Map<String, String>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM `" + t + "` ORDER BY `" + PK.get(t) + "`")) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String v = rs.getString(i);
                            row.put(md.getColumnLabel(i), v == null ? null : HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8)));
                        }
                        rows.add(row);
                    }
                }
                out.put(t, rows);
            }
        }
        return out;
    }

    private static String text(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }

    private static JsonNode load(String resource) throws IOException {
        try (var in = ReceiveGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少黄金样本 " + resource);
            }
            return JSON.readTree(in);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(database == null ? adminUrl : jdbcUrlFor(database), adminUser, adminPassword);
    }

    private static String jdbcUrlFor(String database) {
        int q = adminUrl.indexOf('?');
        String base = q < 0 ? adminUrl : adminUrl.substring(0, q);
        String query = q < 0 ? "" : adminUrl.substring(q);
        return (base.endsWith("/") ? base : base + "/") + database + query;
    }

    private static List<String> installStatements() throws Exception {
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(INSTALL_SQL, StandardCharsets.UTF_8)) {
            if (!line.startsWith("--")) {
                kept.add(line);
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : String.join("\n", kept).split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }
}
