package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRange;
import com.maccms.backend.content.application.LegacyTagAttributes;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.VideoSummaryDto;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B05（MACC-29）旧模板标签专属条件与 SSR tid 的内部查询能力：真实 MySQL 上与旧版 PHP 黄金样本对照（./mvnw verify -Pmysql-it）。
 *
 * <p>流程：新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构与同一份合成数据（content/vodtag/seed.sql）→ 取表结构指纹
 * → 启动完整应用（打开搜索开关、site 与 SSR 搜索页）→ 对 php-golden.json（PHP 8.0）与 php73-golden.json（PHP 7.3）的每个用例：把旧标签属性 / URL
 * 参数经 {@link LegacyTagAttributes}、{@link LegacyRange}、{@link PhpIntval} 换成 {@link VideoLegacyConditions}，调用
 * {@link ContentReadService#listVideos}，断言 ID 序列（旧结果按新版可见范围过滤后的顺序；取窗口的用例在过滤后的完整结果上取同一窗口）
 * 与 total（count 与列表条件一致）；旧代码报错的用例断言新版在构造条件时拒绝；文档登记的差异逐条断言 → SSR 搜索页：每个 tid 用例以真实
 * HTTP 请求 {@code vod/search.html?tid=…}，沿页面实际生成的"下一页"链接取完所有页，ID 序列与 total 同上（链接往返）→ 请求前后全部表不变
 * → 删库前比对表结构指纹 →
 * 只删除该一次性库并确认已不存在。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VideoTagGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path SEED = Path.of("src", "test", "resources", "content", "vodtag", "seed.sql");
    private static final List<String> GOLDEN_FILES = List.of("php-golden.json", "php73-golden.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 新版可见的视频分类（seed.sql 注释）：9 停用、12 上级停用、11 游客无列表权限。 */
    private static final Set<Integer> VISIBLE_TYPES = Set.of(1, 6, 7, 10, 2, 8);
    /**
     * 旧版能执行、新版拒绝的写法（15 文档第 2.4 节）：范围条件的 like（旧版把点击数当字符串模糊匹配）。
     */
    private static final Set<String> LEGACY_ONLY_RANGES = Set.of("like 3%");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static ContentReadService content;
    private static String baseUrl;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SEARCH = "/index.php/vod/search.html";
    private static final Map<String, JsonNode> GOLDEN = new LinkedHashMap<>();

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        for (String f : GOLDEN_FILES) {
            try (InputStream in = VideoTagGoldenMySqlIT.class.getResourceAsStream("/content/vodtag/" + f)) {
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
                "--maccms.content.search.enabled=true",
                "--maccms.content.search.max-concurrent=2",
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class).run(args.toArray(String[]::new));
        content = app.getBean(ContentReadService.class);
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

    // ------------------------------------------------------------------ 与旧版对照

    @Test
    @Order(1)
    void everyLegacyCaseMatchesOrIsADocumentedRejection() throws Exception {
        Map<String, String> before = snapshot();
        for (String file : GOLDEN_FILES) {
            JsonNode g = GOLDEN.get(file);
            Map<Long, Integer> rowTypes = new HashMap<>();
            g.get("rowTypes").properties().forEach(e -> rowTypes.put(Long.parseLong(e.getKey()), e.getValue().asInt()));
            Map<String, Long> times = new HashMap<>();
            g.get("strtotime").forEach(v -> times.put(v.get("input").asString(), v.get("value").asLong()));
            int compared = 0;
            int rejected = 0;
            for (JsonNode c : g.get("cases")) {
                String id = file + " " + c.get("id").asString();
                if (c.has("error")) {
                    // 旧代码抛异常（范围条件只有一段、exp 等）：新版在构造条件时就拒绝，不查库
                    assertThatThrownBy(() -> toQuery(c, times)).as(id).isInstanceOf(IllegalArgumentException.class);
                    rejected++;
                    continue;
                }
                if (legacyOnlyRange(c)) {
                    assertThat(c.get("ids").size()).as(id + " 旧版执行了").isGreaterThanOrEqualTo(0);
                    assertThatThrownBy(() -> toQuery(c, times)).as(id + " 新版拒绝").isInstanceOf(IllegalArgumentException.class);
                    rejected++;
                    continue;
                }
                List<Long> expectedFull = visible(c.has("fullIds") ? c.get("fullIds") : c.get("ids"), rowTypes);
                List<Long> expected = expectedFull;
                VideoListQuery q = toQuery(c, times);
                if (c.has("fullIds")) {
                    int from = Math.min(expectedFull.size(), q.legacy().startOffset());
                    expected = expectedFull.subList(from, Math.min(expectedFull.size(), from + q.pageSize()));
                }
                if (file.equals("php73-golden.json") && c.get("id").asString().equals("type.current-none")) {
                    // PHP 7.3：0 != 'all' 为假（'all' 转成 0），不筛选；PHP 8 为字符串比较，筛选全部顶级分类。新版按 PHP 8
                    assertThat(c.get("ids").size()).as(id + " 旧版 7.3 不筛选").isEqualTo(46);
                    expected = idsOf(content.listVideos(q));
                    assertThat(expected).as(id + " 新版只有顶级分类 1、2 的行").allMatch(v -> Set.of(1, 2)
                            .contains(rowTypes.get(v)));
                    continue;
                }
                PageResponse<VideoSummaryDto> r = content.listVideos(q);
                assertThat(idsOf(r)).as(id + " ID 序列（旧结果按新版可见范围过滤）").isEqualTo(expected);
                assertThat(r.total()).as(id + " total（count 与列表条件一致）").isEqualTo(expectedFull.size());
                compared++;
            }
            assertThat(compared).as(file + " 对照用例数").isGreaterThanOrEqualTo(150);
            assertThat(rejected).as(file + " 拒绝用例数").isEqualTo(16);
        }
        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    /** tid 的逐键零值归一：intval 为 0（含 0、空、abc、0x6、全角数字）时不筛选，负数照常筛选（无结果），不归一为不筛选。 */
    @Test
    @Order(2)
    void tidZeroMeansNoFilterButNegativeStillFilters() {
        long all = content.listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, 100)).total();
        for (String raw : List.of("0", "", "abc", "0x6", "６")) {
            assertThat(PhpIntval.of(raw)).as(raw).isZero();
        }
        VideoListQuery negative = new VideoListQuery(null, VideoSort.TIME, true, 1, 100).withLegacy(
                VideoLegacyConditions.builder().tid(-1L).build());
        assertThat(content.listVideos(negative).total()).isZero();
        assertThat(all).isEqualTo(visibleCount());
    }

    /**
     * SSR 搜索页的 tid（队长裁定 Q2）：真实 HTTP 下每个 tid 用例（旧 vod/search 的分页标签，URL 参数 tid）沿页面上实际生成的
     * "下一页"链接取完所有页，ID 序列 = 旧结果按新版可见范围过滤，"共 N 条" = total；canonical 为 intval 后的规范形态，
     * intval 为 0 时没有 tid 条件（与无条件的筛选结果相同）。
     */
    @Test
    @Order(3)
    void ssrSearchTidMatchesLegacyAcrossFollowedPages() throws Exception {
        Map<String, String> before = snapshot();
        JsonNode g = GOLDEN.get("php-golden.json");
        Map<Long, Integer> rowTypes = new HashMap<>();
        g.get("rowTypes").properties().forEach(e -> rowTypes.put(Long.parseLong(e.getKey()), e.getValue().asInt()));
        int n = 0;
        for (JsonNode c : g.get("cases")) {
            if (!c.get("kind").asString().equals("url") || !"vod/search".equals(lp(c, "pageurl")) || c.get("lp").has("isend")) {
                continue;
            }
            String raw = c.get("query").get("tid").asString();
            String id = c.get("id").asString();
            List<Long> expected = visible(c.get("ids"), rowTypes);
            long tid = PhpIntval.of(raw);
            String url = SEARCH + "?tid=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
            if (tid == 0 && raw.isEmpty()) {
                // tid= 空值：没有任何条件，搜索页显示表单与提示、不查库（MACC-20）
                Document empty = page(url, 200);
                assertThat(empty.select(".search-summary")).as(id).isEmpty();
                continue;
            }
            if (tid == 0) {
                // intval 为 0：不筛选（旧版同样不筛选）——条件为空，与无条件搜索页相同
                assertThat(page(url, 200).select(".search-summary")).as(id).isEmpty();
                continue;
            }
            List<Long> got = new ArrayList<>();
            Document doc = page(url, 200);
            assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).as(id + " canonical")
                    .isEqualTo("http://maccms.local.test/index.php/vod/search.html?tid=" + tid);
            assertThat(doc.selectFirst(".search-summary").text()).as(id + " total").isEqualTo("共 " + expected.size() + " 条");
            assertThat(doc.selectFirst("form.search-form input[name=tid]").attr("value")).as(id + " 隐藏字段")
                    .isEqualTo(Long.toString(tid));
            for (int guard = 0; guard < 10; guard++) {
                got.addAll(cardIds(doc));
                Element next = doc.selectFirst("a.pagination__link--next");
                if (next == null) {
                    break;
                }
                doc = page(next.attr("href"), 200);
            }
            assertThat(got).as(id + " SSR 沿分页链接取完的 ID 序列").isEqualTo(expected);
            n++;
        }
        assertThat(n).as("SSR tid 用例数").isGreaterThanOrEqualTo(15);
        // tid 与新键并存（同一组条件经 content 一次查询）、不可见分类的 tid 只命中 type_id_1 等于它的可见行
        Document combo = page(SEARCH + "?tid=1&letter=" + URLEncoder.encode("0-9", StandardCharsets.UTF_8), 200);
        assertThat(cardIds(combo)).isEmpty();
        Document hidden = page(SEARCH + "?tid=9", 200);
        assertThat(hidden.selectFirst(".search-summary").text()).isEqualTo("共 0 条");
        assertThat(hidden.select(".filter-group a.filter-chip--current")).extracting(Element::text).contains("分类 9");
        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    private static Document page(String pathAndQuery, int status) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).as(pathAndQuery).isEqualTo(status);
        return Jsoup.parse(r.body());
    }

    private static List<Long> cardIds(Document doc) {
        List<Long> out = new ArrayList<>();
        doc.select(".video-grid a.video-card__link").forEach(a ->
                out.add(Long.parseLong(a.attr("href").replaceAll(".*/id/([0-9]+)\\.html$", "$1"))));
        return out;
    }

    // ------------------------------------------------------------------ 条件换算（与旧标签调用链相同的顺序）

    private static boolean legacyOnlyRange(JsonNode c) {
        for (String k : List.of("hits", "hitsday", "hitsweek", "hitsmonth")) {
            if (c.get("lp").has(k) && LEGACY_ONLY_RANGES.contains(c.get("lp").get(k).asString())) {
                return true;
            }
        }
        return false;
    }

    private static String lp(JsonNode c, String key) {
        JsonNode v = c.get("lp").get(key);
        return v == null ? null : v.asString();
    }

    /** 旧 !empty()：null、''、'0' 为空。 */
    private static boolean present(String v) {
        return v != null && !v.isEmpty() && !v.equals("0");
    }

    static VideoListQuery toQuery(JsonNode c, Map<String, Long> times) {
        VideoLegacyConditions.Builder b = VideoLegacyConditions.builder();
        Integer current = c.get("currentType").isNull() ? null : c.get("currentType").asInt();
        LegacyTagAttributes.zeroOrOne(lp(c, "isend")).ifPresent(b::isEnd);
        LegacyTagAttributes.zeroOrOne(lp(c, "plot")).ifPresent(b::plot);
        LegacyTagAttributes.textList(lp(c, "tv")).ifPresent(b::tvs);
        if (present(lp(c, "weekday"))) {
            b.weekday(lp(c, "weekday"));
        }
        if (present(lp(c, "timeadd"))) {
            b.addedAfter(times.get(lp(c, "timeadd")));
        }
        if (present(lp(c, "timehits"))) {
            b.hitsTimeAfter(times.get(lp(c, "timehits")));
        }
        if (present(lp(c, "time"))) {
            b.updatedAfter(times.get(lp(c, "time")));
        }
        if (present(lp(c, "hitsmonth"))) {
            b.hitsMonth(LegacyRange.parseLegacy(lp(c, "hitsmonth")));
        }
        if (present(lp(c, "hitsweek"))) {
            b.hitsWeek(LegacyRange.parseLegacy(lp(c, "hitsweek")));
        }
        if (present(lp(c, "hitsday"))) {
            b.hitsDay(LegacyRange.parseLegacy(lp(c, "hitsday")));
        }
        if (present(lp(c, "hits"))) {
            b.hits(LegacyRange.parseLegacy(lp(c, "hits")));
        }
        LegacyTagAttributes.intList(lp(c, "not")).ifPresent(b::notIds);
        LegacyTagAttributes.rel(lp(c, "rel")).ifPresent(r -> {
            if (r.ids() != null) {
                b.relIds(r.ids());
            } else {
                b.rel(r.text());
            }
        });
        LegacyTagAttributes.smallIntList(lp(c, "typenot")).ifPresent(b::typeNot);
        Integer categoryId = null;
        boolean pagedCategory = "vod/type".equals(lp(c, "pageurl"));
        if (pagedCategory) {
            // 旧分类页的分页标签：$type = 当前分类（Vod.php:251-253），与 type 属性是同一个变量
            categoryId = current;
        } else {
            LegacyTagAttributes.types(lp(c, "type"), current == null ? 0 : current).ifPresent(b::types);
        }
        LegacyTagAttributes.levels(lp(c, "level")).ifPresent(b::levels);
        b.start(LegacyTagAttributes.absInt(lp(c, "start")));
        if (c.get("query").has("tid")) {
            long tid = PhpIntval.of(c.get("query").get("tid").asString());
            if (tid != 0) {
                b.tid(tid);
            }
        }
        // 生成脚本的默认 num 为 999（取完整结果，新版取页大小上限 100）；旧版 num 为空或 0 时取 20（Vod.php:154-156）
        int num = lp(c, "num") == null ? 999 : LegacyTagAttributes.absInt(lp(c, "num"));
        int pageSize = num == 0 ? 20 : Math.min(num, VideoListQuery.MAX_PAGE_SIZE);
        VideoSort sort = VideoSort.fromApiValue(lp(c, "by") == null ? "time" : lp(c, "by")).orElseThrow();
        boolean desc = !"asc".equals(lp(c, "order"));
        VideoFilter filter = present(lp(c, "ids")) ? new VideoFilter(null, null, null, null, null, null, null, null,
                null, null, null, null, null, lp(c, "ids")) : VideoFilter.NONE;
        return new VideoListQuery(categoryId, sort, desc, 1, pageSize, filter, false, b.build());
    }

    private static List<Long> visible(JsonNode ids, Map<Long, Integer> rowTypes) {
        List<Long> out = new ArrayList<>();
        ids.forEach(n -> {
            if (VISIBLE_TYPES.contains(rowTypes.get(n.asLong()))) {
                out.add(n.asLong());
            }
        });
        return out;
    }

    private static List<Long> idsOf(PageResponse<VideoSummaryDto> r) {
        return r.items().stream().map(VideoSummaryDto::id).toList();
    }

    private static long visibleCount() {
        Map<Long, Integer> rowTypes = new HashMap<>();
        GOLDEN.get("php-golden.json").get("rowTypes").properties()
                .forEach(e -> rowTypes.put(Long.parseLong(e.getKey()), e.getValue().asInt()));
        return rowTypes.entrySet().stream().filter(e -> e.getKey() <= 46 && VISIBLE_TYPES.contains(e.getValue())).count();
    }

    // ------------------------------------------------------------------ 工具

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
}
