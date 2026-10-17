package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.content.application.VideoRandom;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
 * B05（队长裁定 D-B05-5）模块筛选页（show）与搜索页（search）的 PHP 黄金对照（./mvnw verify -Pmysql-it）。
 *
 * <p>新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构与合成数据 site/module-search/seed.sql（与
 * backend/src/test/php/module_search_golden.php 相同）→ 以真实 HTTP 启动应用（site、搜索页、旧 app.show、app.ajax_page、包含匹配
 * 搜索都打开）→ 逐条请求 php-golden.json 中每个用例的 SSR 地址，从页面卡片的详情链接取 ID 序列（沿"下一页"取完所有页）与旧版
 * 比对，并检查 canonical 是旧逐键归一后的规范地址（链接往返）、"共 N 条"等于旧 total 且与各页合计一致、ajax 片段与第一页相同；
 * 随机排序用例用可替换的随机源按黄金样本给出随机页/候选下标，并核对上限 floor(c/20)+1 或候选范围 → 主用例之后导入
 * phase-plot-bulk.sql 再跑剧情经视频链的阈值用例 → 表结构不变 → 删除该一次性库。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
class ModuleSearchGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path SEED = Path.of("src", "test", "resources", "site", "module-search", "seed.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern DETAIL_ID = Pattern.compile("/detail/id/([0-9]+)\\.html$");
    /**
     * 与旧版的已登记差异（15 文档第 11 节）：旧版 LIKE 模式不转义 {@code _ %}（{@code wd=_} 命中所有含任意字符的行），新版按字面值匹配
     * （与视频的 keyword/tag 相同）。值为新版的 ID 序列。
     */
    private static final Map<String, List<Integer>> DIFFERENCES = Map.of("art.search.wd=_", List.of(6));
    private static final ScriptedRandom RANDOM = new ScriptedRandom();

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
        try (InputStream in = ModuleSearchGoldenMySqlIT.class.getResourceAsStream("/site/module-search/php-golden.json")) {
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
                "--maccms.site.features.show=true",
                "--maccms.site.features.ajax-page=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/"));
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

    /** 只按黄金样本给出随机页或候选下标，并记录调用参数；没有给值时抛异常（请求变成 500，测试失败）。 */
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
            calls.add(new long[] {max});
            if (nextPage == null || nextPage > max) {
                throw new IllegalStateException("未按黄金样本给出随机页");
            }
            long p = nextPage;
            nextPage = null;
            return p;
        }

        @Override
        public synchronized long[] sampleIndexes(long n, int k) {
            calls.add(new long[] {n, k});
            if (nextSample == null) {
                throw new IllegalStateException("未按黄金样本给出候选");
            }
            long[] s = nextSample;
            nextSample = null;
            return s;
        }
    }

    @Test
    void everyGoldenCaseListsTheSameIdsAsTheLegacyPage() throws Exception {
        assertThat(golden.get("php").asString()).startsWith("8.0");
        int checked = 0;
        int nonEmpty = 0;
        int multiPage = 0;
        int random = 0;
        String phase = "base";
        for (JsonNode c : golden.get("cases")) {
            String casePhase = c.get("phase").asString();
            if (!casePhase.equals(phase)) {
                // 附加数据在主用例之后导入（与 module_search_golden.php 相同的顺序）
                try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
                    for (String sql : statements(SEED.resolveSibling("phase-" + casePhase + ".sql"))) {
                        st.execute(sql);
                    }
                }
                phase = casePhase;
            }
            String id = c.get("id").asString();
            String path = c.get("path").asString();
            List<Integer> expected = new ArrayList<>();
            c.get("ids").forEach(n -> expected.add(n.asInt()));
            boolean rnd = c.has("algorithm");
            script(c);
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(id + " " + r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("cache-control")).as(id).hasValue("no-store");
            Document doc = Jsoup.parse(r.body());
            if (rnd) {
                assertRandomCalls(id, c);
            }
            // 链接往返：规范地址就是旧逐键归一后、PHP urlencode + 按键排序生成的地址
            assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).as(id)
                    .isEqualTo("http://maccms.local.test" + c.get("canonical").asString());
            // count 与 list 一致："共 N 条"是旧版的 total；非随机时沿"下一页"取完所有页
            long total = DIFFERENCES.containsKey(id) ? DIFFERENCES.get(id).size() : c.get("total").asLong();
            String summary = "共 " + total + " 条";
            assertThat(doc.selectFirst(".search-summary").text()).as(id).isEqualTo(summary);
            List<Integer> ids = cardIds(doc.select(".content-grid .content-card__link"));
            List<Integer> firstPage = List.copyOf(ids);
            if (rnd) {
                assertThat(doc.select("nav.pagination")).as(id + " 随机排序不显示分页").isEmpty();
                assertThat(doc.selectFirst(".search-random-notice")).as(id).isNotNull();
                random++;
            } else {
                Element next = doc.selectFirst("nav.pagination a[rel=next]");
                if (next != null) {
                    multiPage++;
                }
                while (next != null) {
                    Document d = Jsoup.parse(get(next.attr("href")).body());
                    assertThat(d.selectFirst(".search-summary").text()).as(id).isEqualTo(summary);
                    ids.addAll(cardIds(d.select(".content-grid .content-card__link")));
                    next = d.selectFirst("nav.pagination a[rel=next]");
                }
                assertThat((long) ids.size()).as(id + " 各页合计等于 total").isEqualTo(total);
            }
            assertThat(ids).as(id).isEqualTo(DIFFERENCES.getOrDefault(id, expected));
            // ajax 片段：同一结果（第一页）、没有外壳
            String ajax = path.replaceFirst("/(search|show)", "/ajax_$1");
            script(c);
            HttpResponse<String> f = get(ajax);
            assertThat(f.statusCode()).as(ajax).isEqualTo(200);
            assertThat(f.body()).as(ajax).doesNotContain("<html", "<script", "<form");
            assertThat(cardIds(Jsoup.parseBodyFragment(f.body()).select(".content-card__link"))).as(ajax)
                    .isEqualTo(firstPage);
            checked++;
            if (!ids.isEmpty()) {
                nonEmpty++;
            }
        }
        assertThat(checked).isEqualTo(golden.get("cases").size()).isGreaterThanOrEqualTo(120);
        assertThat(nonEmpty).isGreaterThanOrEqualTo(100);
        assertThat(multiPage).as("多页用例（沿下一页取完）").isGreaterThanOrEqualTo(4);
        assertThat(random).as("随机排序用例").isGreaterThanOrEqualTo(15);
    }

    /** 随机排序用例：按黄金样本给出随机页（算法一）或候选下标（剧情经视频链、计数大于 2000 的算法二）。 */
    private static void script(JsonNode c) {
        if (!c.has("algorithm")) {
            RANDOM.script(null, null);
        } else if (c.get("algorithm").asString().equals("page")) {
            RANDOM.script(c.get("page").asLong(), null);
        } else {
            long[] sample = new long[c.get("candidates").size()];
            for (int i = 0; i < sample.length; i++) {
                sample[i] = c.get("candidates").get(i).asLong();
            }
            Arrays.sort(sample);
            RANDOM.script(null, sample);
        }
    }

    private static void assertRandomCalls(String id, JsonNode c) {
        List<long[]> calls = RANDOM.calls;
        assertThat(calls).as(id).hasSize(1);
        if (c.get("algorithm").asString().equals("page")) {
            assertThat(calls.getFirst()).as(id + " 随机页上限 floor(c/20)+1").containsExactly(c.get("pageTotal").asLong());
        } else {
            long n = c.get("idRange").get(1).asLong() - c.get("idRange").get(0).asLong() + 1;
            assertThat(calls.getFirst()).as(id + " 候选下标范围与个数").containsExactly(n, 1000L);
        }
    }

    @Test
    void invalidAndHiddenInputsAre400Or404WithoutSql() throws Exception {
        RANDOM.script(null, null);
        for (String p : List.of("/index.php/art/search.html?foo=1", "/index.php/art/search.html?by=in",
                "/index.php/role/search.html?tid=1", "/index.php/website/search.html?order=up",
                "/index.php/actor/search.html?letter=" + "A".repeat(65), "/index.php/role/show/id/1.html",
                "/index.php/plot/search.html?isend=1")) {
            assertThat(get(p).statusCode()).as(p).isEqualTo(400);
        }
        for (String p : List.of("/index.php/art/show/id/31.html", "/index.php/art/show/id/9999.html",
                "/index.php/topic/show/id/1.html", "/index.php/plot/show/id/1.html",
                "/index.php/art/search.html?page=2")) {
            assertThat(get(p).statusCode()).as(p).isEqualTo(404);
        }
        // 演员筛选页未给出分类：取第一个可见的演员分类（31，本身与子分类 32）
        Document doc = Jsoup.parse(get("/index.php/actor/show.html").body());
        assertThat(cardIds(doc.select(".content-grid .content-card__link"))).containsExactly(4, 3, 2, 1);
    }

    private static List<Integer> cardIds(List<Element> links) {
        List<Integer> out = new ArrayList<>();
        for (Element a : links) {
            Matcher m = DETAIL_ID.matcher(a.attr("href"));
            assertThat(m.find()).as(a.attr("href")).isTrue();
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 数据库

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
