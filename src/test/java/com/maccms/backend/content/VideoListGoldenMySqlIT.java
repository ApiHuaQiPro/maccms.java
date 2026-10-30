package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.content.application.VideoRandom;
import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 契约 1.3.0（B04）视频列表筛选参数与随机排序的真实 MySQL 集成测试（./mvnw verify -Pmysql-it），与旧版 PHP 黄金样本对照。
 *
 * <p>流程：新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构 → 导入与 PHP 黄金样本相同的合成数据
 * （src/test/resources/content/vodlist/seed.sql）→ 追加只对新版可见范围有影响的行（不可见分类下的视频，新版不列出）→ 取表结构指纹
 * → 以真实 HTTP 启动应用（打开搜索开关；随机源替换为按黄金样本给值的实现）→ 逐条请求 php-golden.json（PHP 8.0）与
 * php73-golden.json（PHP 7.3）中 compare=equal 的用例，比对 ID 序列、总数与第 2 页窗口；legacy-only 用例逐条断言文档记录的差异
 * → 随机排序：把旧代码实际使用的随机页 / 候选 ID 交给新代码，比对结果；依次执行 phase-sparse.sql、phase-high.sql 后重复
 * → 校验契约、无字段泄露、请求不改变任何表 → 删库前比对表结构指纹 → 只删除该一次性库并确认已不存在。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VideoListGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path DATA_DIR = Path.of("src", "test", "resources", "content", "vodlist");
    private static final List<String> GOLDEN_FILES = List.of("php-golden.json", "php73-golden.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENTINEL = "SENTINEL";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** 旧 URL 参数名 → 新接口参数名（14 文档第 2 节）。 */
    private static final Map<String, String> API_NAMES = Map.of("wd", "keyword", "class", "classes", "by", "sort");
    private static final ScriptedRandom RANDOM = new ScriptedRandom();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;
    private static OpenApiContract contract;
    private static final Map<String, JsonNode> GOLDEN = new LinkedHashMap<>();

    /** 只按测试给定的值返回；没有给值时抛异常（请求变成 500，测试失败），并记录每次调用的参数。 */
    static final class ScriptedRandom extends VideoRandom {
        private Long nextPage;
        private long[] nextSample;
        final List<long[]> calls = new ArrayList<>();

        synchronized void script(Long page, long[] sample) {
            nextPage = page;
            nextSample = sample;
            calls.clear();
        }

        @Override
        public synchronized long pageBetweenOneAnd(long max) {
            calls.add(new long[]{max});
            if (nextPage == null || nextPage > max) {
                throw new IllegalStateException("未按黄金样本给出随机页");
            }
            long p = nextPage;
            nextPage = null;
            return p;
        }

        @Override
        public synchronized long[] sampleIndexes(long n, int k) {
            calls.add(new long[]{n, k});
            if (nextSample == null) {
                throw new IllegalStateException("未按黄金样本给出候选");
            }
            long[] s = nextSample;
            nextSample = null;
            return s;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
        for (String f : GOLDEN_FILES) {
            try (InputStream in = VideoListGoldenMySqlIT.class.getResourceAsStream("/content/vodlist/" + f)) {
                GOLDEN.put(f, JSON.readTree(in));
            }
        }

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
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
            }
            for (String sql : statements(DATA_DIR.resolve("seed.sql"))) {
                st.execute(sql);
            }
            seedOutsideVisibleScope(st);
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }

        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.content.search.enabled=true",
                "--maccms.content.search.max-concurrent=2"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean("scriptedVideoRandom",
                        VideoRandom.class, () -> RANDOM, bd -> bd.setPrimary(true)))
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
                List<String> schemaDiff = schemaDiffSinceImport();
                try (Connection c = admin(null); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE `" + dbName + "`");
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                            + "WHERE schema_name = '" + dbName + "'")) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                    }
                }
                assertThat(schemaDiff).as("B02 表结构护栏：测试结束时表结构与导入 install.sql 时一致").isEmpty();
                System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            }
        }
    }

    /**
     * 新版可见范围之外的行（分类 3：游客没有列表权限）：旧版列表不按分类可见性过滤会列出它们，新版不列出；黄金样本生成时没有这些行，
     * 所以新版结果仍应与黄金样本逐项相同。ID 在 1..3001 之间，不改变随机排序所用的全表 MIN/MAX；文本列带 SENTINEL，响应中出现即为泄露。
     */
    private static void seedOutsideVisibleScope(Statement st) throws SQLException {
        st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_extend) "
                + "VALUES (3,'隐藏视频分类','en3',1,1,0,1,'')");
        st.execute("UPDATE mac_group SET group_type=CONCAT(group_type, '3,') WHERE group_id=1");
        for (int id : new int[]{41, 42}) {
            st.execute("INSERT INTO mac_vod (vod_id,type_id,vod_name,vod_status,vod_letter,vod_state,vod_version,vod_year,"
                    + "vod_actor,vod_director,vod_tag,vod_time,vod_hits_week,vod_content,vod_play_url,vod_down_url,"
                    + "vod_plot_name,vod_plot_detail) VALUES (" + id + ",3,'" + SENTINEL + id + "',1,'A','正片','高清版',"
                    + "'2020','演员甲','导演乙','标签甲',1999999999,999999,'" + SENTINEL + "','','','','')");
        }
    }

    // ------------------------------------------------------------------ 工具

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

    /** 与 PHP 生成脚本相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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

    private static void runPhase(String file) throws Exception {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(DATA_DIR.resolve(file))) {
                st.execute(sql);
            }
        }
    }

    private static List<String[]> sortedParams(JsonNode query, Map<String, String> extra) {
        List<String[]> out = new ArrayList<>();
        query.properties().forEach(e -> out.add(new String[]{API_NAMES.getOrDefault(e.getKey(), e.getKey()),
                e.getValue().asString()}));
        extra.forEach((k, v) -> out.add(new String[]{k, v}));
        return out;
    }

    /** 黄金样本中 api 字段是未编码的可读形式；这里从 query 对象重建，核对与 api 一致后按 UTF-8 百分号编码发送。 */
    private static String apiFor(JsonNode c, Map<String, String> extra, String expectedReadable) {
        List<String[]> params = sortedParams(c.get("query"), extra);
        StringBuilder readable = new StringBuilder("/api/v1/videos?");
        StringBuilder encoded = new StringBuilder("/api/v1/videos?");
        for (int i = 0; i < params.size(); i++) {
            String sep = i == 0 ? "" : "&";
            readable.append(sep).append(params.get(i)[0]).append('=').append(params.get(i)[1]);
            encoded.append(sep).append(params.get(i)[0]).append('=')
                    .append(URLEncoder.encode(params.get(i)[1], StandardCharsets.UTF_8));
        }
        if (expectedReadable != null) {
            assertThat(readable.toString()).as(c.get("id") + " api").isEqualTo(expectedReadable);
        }
        return encoded.toString();
    }

    private record Resp(int status, JsonNode body, String raw) {
    }

    private static Resp get(String encodedPathAndQuery) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + encodedPathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), JSON.readTree(r.body()), r.body());
    }

    private static Resp checked(String encoded, int expectedStatus) throws Exception {
        Resp r = get(encoded);
        assertThat(r.status()).as(encoded + " 状态码，响应: " + r.raw()).isEqualTo(expectedStatus);
        contract.assertMatches(r.body(), contract.responseSchema("/api/v1/videos", "get", expectedStatus));
        assertThat(r.raw()).as(encoded + " 不得出现新版可见范围之外的内容").doesNotContain(SENTINEL);
        return r;
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private static Map<String, String> snapshot() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                try (ResultSet rs = st.executeQuery("CHECKSUM TABLE `" + t + "` EXTENDED")) {
                    rs.next();
                    out.put(t + ".checksum", rs.getString(2));
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + t + "`")) {
                    rs.next();
                    out.put(t + ".rows", rs.getString(1));
                }
            }
        }
        return out;
    }

    private static List<String> schemaDiffSinceImport() {
        if (schemaAtImport == null) {
            return List.of();
        }
        try (Connection c = admin(dbName)) {
            return SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName));
        } catch (SQLException e) {
            return List.of("读取表结构指纹失败：" + e.getClass().getSimpleName());
        }
    }

    private static List<JsonNode> cases(String file, String phase, boolean random) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : GOLDEN.get(file).get("cases")) {
            if (c.get("phase").asString().equals(phase) && c.has("algorithm") == random) {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 与旧版黄金样本对照

    @Test
    @Order(1)
    void filtersMatchLegacyIdSequencesTotalsAndPagesAndHaveNoSideEffects() throws Exception {
        Map<String, String> before = snapshot();
        for (String file : GOLDEN_FILES) {
            int equal = 0;
            for (JsonNode c : cases(file, "base", false)) {
                if (!c.get("compare").asString().equals("equal")) {
                    continue;
                }
                equal++;
                String id = file + " " + c.get("id").asString();
                Resp full = checked(apiFor(c, Map.of("pageSize", "100"), c.get("api").asString()), 200);
                assertThat(ids(full.body().get("items"))).as(id).isEqualTo(longs(c.get("ids")));
                assertThat(full.body().get("total").asLong()).as(id + " total（count 与列表条件一致）")
                        .isEqualTo(c.get("total").asLong());
                Resp p2 = checked(apiFor(c, new LinkedHashMap<>(Map.of("pageSize", "7", "page", "2")), null), 200);
                assertThat(ids(p2.body().get("items"))).as(id + " 第 2 页（每页 7 条）").isEqualTo(longs(c.get("page2Ids")));
                assertThat(p2.body().get("total").asLong()).isEqualTo(c.get("total").asLong());
            }
            assertThat(equal).as(file + " equal 用例数").isGreaterThanOrEqualTo(63);
        }
        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    /** legacy-only 用例：旧版结果来自黄金样本，新版结果是文档记录的差异（14 文档第 5 节）。每个用例都必须有断言。 */
    @Test
    @Order(2)
    void legacyOnlyCasesShowTheDocumentedDifferences() throws Exception {
        Map<String, Consumer<JsonNode>> expectations = new LinkedHashMap<>();
        expectations.put("letter.0", c -> {
            // 旧版 empty("0") 视为未传（整表）；新版 letter=0 是字面值：只有首字母为 0 的行（3、13、23、33）
            assertThat(c.get("total").asLong()).isEqualTo(2039);
            assertThat(newIds("letter=0")).containsExactlyInAnyOrder(3L, 13L, 23L, 33L);
        });
        expectations.put("letter.truncated", c -> {
            // 旧版按 search_len=10 截断（PHP 8 下 ASCII 字母还会变成空格，PHP 7.3 下为 A,B,C,D,E,），新版不截断
            assertThat(c.get("total").asLong()).isIn(4L, 19L);
            assertThat(newTotal("letter=" + enc("A,B,C,D,E,F"))).isEqualTo(15);
        });
        expectations.put("year.0003-0006", c -> {
            assertThat(longs(c.get("ids"))).containsExactly(35L);
            assertThat(newTotal("year=0003-0006")).as("新版展开为 0003..0006，不命中年份为 5 的行").isZero();
        });
        expectations.put("year.0005", c -> {
            assertThat(longs(c.get("ids"))).containsExactly(35L);
            assertThat(newTotal("year=0005")).isZero();
        });
        expectations.put("year.2020年", c -> {
            assertThat(c.get("total").asLong()).as("旧版不筛选").isEqualTo(2039);
            assertThat(status("year=" + enc("2020年"))).isEqualTo(400);
        });
        expectations.put("ids.0", c -> {
            assertThat(c.get("total").asLong()).isEqualTo(2039);
            assertThat(newTotal("ids=0")).isZero();
        });
        expectations.put("actor.entity", c -> {
            // 旧版只匹配实体形式（Tom &amp; Jerry 的行）；新版原值与实体形式都匹配，是旧版结果的超集
            List<Long> legacy = longs(c.get("ids"));
            List<Long> now = newIds("actor=" + enc("m & J"));
            assertThat(now).containsAll(legacy).hasSize(legacy.size() * 2);
        });
        expectations.put("actor.truncated", c -> {
            assertThat(c.get("total").asLong()).isEqualTo(4);
            assertThat(newTotal("actor=" + enc("演员甲演员乙演员丙演员丁"))).as("新版不截断，整串无命中").isZero();
        });
        for (String file : GOLDEN_FILES) {
            for (JsonNode c : cases(file, "base", false)) {
                if (c.get("compare").asString().equals("legacy-only")) {
                    Consumer<JsonNode> e = expectations.get(c.get("id").asString());
                    assertThat(e).as(file + " " + c.get("id") + " 缺少差异断言").isNotNull();
                    e.accept(c);
                }
            }
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static Resp request(String encodedQuery) {
        try {
            return get("/api/v1/videos?" + encodedQuery + "&pageSize=100");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> newIds(String encodedQuery) {
        Resp r = request(encodedQuery);
        assertThat(r.status()).as(encodedQuery + " → " + r.raw()).isEqualTo(200);
        assertThat(r.raw()).doesNotContain(SENTINEL);
        return ids(r.body().get("items"));
    }

    private static long newTotal(String encodedQuery) {
        Resp r = request(encodedQuery);
        assertThat(r.status()).as(encodedQuery + " → " + r.raw()).isEqualTo(200);
        return r.body().get("total").asLong();
    }

    private static int status(String encodedQuery) {
        return request(encodedQuery).status();
    }

    // ------------------------------------------------------------------ 随机排序

    private void assertRandomCasesMatch(String phase, int expectedCases) throws Exception {
        Map<String, String> before = snapshot();
        int n = 0;
        for (String file : GOLDEN_FILES) {
            for (JsonNode c : cases(file, phase, true)) {
                n++;
                String id = file + " " + c.get("id").asString();
                int num = Integer.parseInt(sortedParamsValue(c, "pageSize"));
                long count = c.get("count").asLong();
                boolean candidates = c.get("algorithm").asString().equals("candidates");
                if (candidates) {
                    long[] sample = new long[c.get("candidates").size()];
                    for (int i = 0; i < sample.length; i++) {
                        sample[i] = c.get("candidates").get(i).asLong();
                    }
                    RANDOM.script(null, sample);
                } else {
                    RANDOM.script(c.get("page").asLong(), null);
                }
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("sort", "rnd");
                extra.put("pageSize", String.valueOf(num));
                Resp r = checked(apiFor(c, extra, c.get("api").asString()), 200);
                assertThat(ids(r.body().get("items"))).as(id).isEqualTo(longs(c.get("ids")));
                assertThat(r.body().get("total").asLong()).as(id + " total").isEqualTo(c.get("total").asLong());
                assertThat(r.body().get("page").asLong()).as(id + " page").isEqualTo(c.get("page").asLong());
                List<long[]> calls = new ArrayList<>(RANDOM.calls);
                assertThat(calls).as(id + " 随机源调用一次").hasSize(1);
                long min = c.get("idRange").get(0).asLong();
                long max = c.get("idRange").get(1).asLong();
                if (candidates) {
                    assertThat(count).isGreaterThan(2000);
                    assertThat(calls.get(0)).as(id + " 候选范围 0..max-min、数量 1000").containsExactly(max - min + 1, 1000);
                } else {
                    assertThat(count).isLessThanOrEqualTo(2000);
                    assertThat(calls.get(0)).as(id + " 随机页上限 floor(c/num)+1").containsExactly(count / num + 1);
                }
            }
        }
        assertThat(n).isEqualTo(expectedCases * GOLDEN_FILES.size());
        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    private static String sortedParamsValue(JsonNode c, String key) {
        String api = c.get("api").asString();
        for (String part : api.substring(api.indexOf('?') + 1).split("&")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        throw new IllegalStateException(c.get("id") + " 缺少 " + key);
    }

    @Test
    @Order(3)
    void randomSortMatchesLegacyOnBothSidesOfTheThreshold() throws Exception {
        assertRandomCasesMatch("base", 5);
        // 与 ids、包含匹配组合：条件先收窄计数（6 ≤ 2000），走随机页算法，唯一的随机页为 1
        RANDOM.script(1L, null);
        Resp r = checked("/api/v1/videos?sort=rnd&ids=" + enc("1,2,3,4,5,999,1500") + "&actor=" + enc("演员甲,")
                + "&pageSize=100", 200);
        assertThat(r.body().get("total").asLong()).isEqualTo(6);
        assertThat(ids(r.body().get("items"))).as("按 hits_week 倒序：批量行 1500 在前").startsWith(1500L)
                .containsExactlyInAnyOrder(1500L, 1L, 2L, 3L, 4L, 5L);
        assertThat(RANDOM.calls).hasSize(1);
        assertThat(RANDOM.calls.get(0)).containsExactly(1L);
    }

    @Test
    @Order(4)
    void randomSortMatchesLegacyWithSparseIds() throws Exception {
        runPhase("phase-sparse.sql");
        assertRandomCasesMatch("sparse", 2);
    }

    @Test
    @Order(5)
    void randomSortMatchesLegacyWhenNoCandidateCanMatch() throws Exception {
        runPhase("phase-high.sql");
        assertRandomCasesMatch("high", 1);
    }

    // ------------------------------------------------------------------ 新版规则（不在旧版对照范围内）

    @Test
    @Order(6)
    void validationAndLimitsOnRealHttp() throws Exception {
        for (String[] bad : new String[][]{{"ids=" + enc(String.join(",", java.util.Collections.nCopies(101, "1"))), "ids"},
                {"year=" + enc("2020-202"), "year"}, {"letter=" + enc(" A"), "letter"}, {"actor=%FF", "actor"},
                {"name=a&name=b", "name"}}) {
            Resp r = checked("/api/v1/videos?" + bad[0], 400);
            assertThat(r.body().get("fieldErrors").get(0).get("field").asString()).as(bad[0]).isEqualTo(bad[1]);
        }
        assertThat(Set.of(GOLDEN.keySet().toArray())).hasSize(2);
    }
}
