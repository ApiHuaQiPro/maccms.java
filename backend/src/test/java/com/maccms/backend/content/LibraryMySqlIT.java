package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.contract.OpenApiContract;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 契约 1.2.0（MACC-27 / B03）真实 MySQL 集成测试（./mvnw verify -Pmysql-it）。
 *
 * <p>流程与 ContentApiMySqlIT 相同：新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql 表结构 →
 * 导入与 PHP 黄金样本相同的合成数据（src/test/resources/content/library/seed.sql）→ 追加只对新版可见范围有影响的行
 * （不可见分类下的内容，新版不列出，所以不改变与旧版对照的结果）→ 以真实 HTTP 启动应用（打开搜索开关，使专题的标签关联生效）
 * → 逐条请求 php-golden.json 中的用例并比对 ID 序列 → 校验契约、无字段泄露、全部旧表无变化 → 删除该一次性库。
 *
 * <p>只用外部提供的管理账号（MACCMS_IT_ADMIN_URL / USERNAME / PASSWORD）；不连接、不读写任何已有库（包括 maccms_dev）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LibraryMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path SEED_SQL = Path.of("src", "test", "resources", "content", "library", "seed.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENTINEL = "SENTINEL";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;
    private static OpenApiContract contract;
    private static JsonNode golden;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
        try (InputStream in = LibraryMySqlIT.class.getResourceAsStream("/content/library/php-golden.json")) {
            golden = JSON.readTree(in);
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
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
            }
            for (String sql : statements(SEED_SQL)) {
                st.execute(sql);
            }
            seedOutsideVisibleScope(st);
        }

        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.content.search.enabled=true"));
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
                try (Connection c = admin(null); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE `" + dbName + "`");
                }
            }
        }
    }

    /**
     * 新版可见范围之外的行：旧版列表会列出它们（不按分类可见性过滤），新版不列出；黄金样本生成时没有这些行，
     * 所以新版结果仍应与黄金样本逐项相同。每行的文本列带 SENTINEL，响应中出现即为泄露。
     */
    private static void seedOutsideVisibleScope(Statement st) throws SQLException {
        // 23 文章分类：游客无列表位；26 文章分类：停用；33 演员分类：停用；43 网址分类：游客无列表位；3 视频分类：游客无列表位
        for (String t : List.of("(23,'隐藏文章分类',1,2,0,1)", "(26,'停用文章分类',1,2,0,0)", "(33,'停用演员分类',1,8,0,0)",
                "(43,'隐藏网址分类',1,11,0,1)", "(3,'隐藏视频分类',1,1,0,1)")) {
            st.execute("INSERT INTO mac_type (type_id,type_name,type_sort,type_mid,type_pid,type_status,type_extend) VALUES "
                    + t.replace(")", ",'')"));
        }
        st.execute("INSERT INTO mac_art (art_id,type_id,art_name,art_status,art_letter,art_level,art_time,art_tag,art_title,"
                + "art_note,art_content) VALUES (13,23,'" + SENTINEL + "A13',1,'A',3,1800000000,'标签甲','','','" + SENTINEL + "'),"
                + "(14,26,'" + SENTINEL + "A14',1,'A',3,1800000000,'标签甲','','','" + SENTINEL + "')");
        st.execute("INSERT INTO mac_actor (actor_id,type_id,actor_name,actor_status,actor_letter,actor_level,actor_time,"
                + "actor_sex,actor_area,actor_content) VALUES (13,33,'" + SENTINEL + "',1,'A',3,1800000000,'男','大陆','')");
        st.execute("INSERT INTO mac_website (website_id,type_id,website_name,website_status,website_letter,website_level,"
                + "website_time,website_area,website_lang,website_content) VALUES (13,43,'" + SENTINEL
                + "',1,'A',3,1800000000,'香港','国语','')");
        // 视频 107：分类 3 不可见，但被专题 1 的 topic_rel_vod 引用，标签也命中专题 2、3
        st.execute("INSERT INTO mac_vod (vod_id,type_id,vod_name,vod_status,vod_tag,vod_time,vod_content,vod_play_url,"
                + "vod_down_url,vod_plot_name,vod_plot_detail) VALUES (107,3,'" + SENTINEL + "V107',1,'标签甲',1700099999,'','','',"
                + "'" + SENTINEL + "','')");
        st.execute("UPDATE mac_topic SET topic_rel_vod=CONCAT(topic_rel_vod, ',107'), topic_rel_art=CONCAT(topic_rel_art, ',13')"
                + " WHERE topic_id=1");
        st.execute("UPDATE mac_group SET group_type=CONCAT(group_type, '3,') WHERE group_id=1");
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

    private record Resp(int status, JsonNode body, String raw) {
    }

    /** 查询串中的值按 UTF-8 百分号编码后发送（黄金样本里的 api 为未编码的可读形式）。 */
    private static Resp get(String pathAndQuery) throws Exception {
        int q = pathAndQuery.indexOf('?');
        String url = pathAndQuery;
        if (q >= 0) {
            StringBuilder sb = new StringBuilder(pathAndQuery.substring(0, q + 1));
            String[] parts = pathAndQuery.substring(q + 1).split("&");
            for (int i = 0; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                sb.append(i == 0 ? "" : "&").append(parts[i], 0, eq + 1)
                        .append(URLEncoder.encode(parts[i].substring(eq + 1), StandardCharsets.UTF_8));
            }
            url = sb.toString();
        }
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), JSON.readTree(r.body()), r.body());
    }

    private static Resp checked(String contractPath, String pathAndQuery, int expectedStatus) throws Exception {
        Resp r = get(pathAndQuery);
        assertThat(r.status()).as(pathAndQuery + " 状态码，响应: " + r.raw()).isEqualTo(expectedStatus);
        contract.assertMatches(r.body(), contract.responseSchema(contractPath, "get", expectedStatus));
        assertThat(r.raw()).as(pathAndQuery + " 不得出现新版可见范围之外的内容").doesNotContain(SENTINEL);
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

    private static String contractPath(String api) {
        String path = api.contains("?") ? api.substring(0, api.indexOf('?')) : api;
        return path.replaceAll("/\\d+(/|$)", "/{id}$1");
    }

    private static String str(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asString();
    }

    /** 一次性库内所有表的 CHECKSUM 与行数，用于证明请求没有任何写入副作用。 */
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

    // ------------------------------------------------------------------ 与旧版黄金样本对照

    @Test
    @Order(1)
    void listsMatchLegacyIdSequencesAndHaveNoSideEffects() throws Exception {
        Map<String, String> before = snapshot();
        assertThat(golden.get("cases").size()).isGreaterThan(150);
        for (JsonNode c : golden.get("cases")) {
            String api = c.get("api").asString();
            List<Long> expected = longs(c.get("ids"));
            Resp r = checked(contractPath(api), api, 200);
            assertThat(ids(r.body().get("items"))).as(c.get("id").asString() + " " + api).isEqualTo(expected);
            assertThat(r.body().get("total").asLong()).as(c.get("id") + " total").isEqualTo(expected.size());

            // 分页窗口：第 2 页（每页 3 条）等于完整序列的对应切片
            String paged = api.replace("pageSize=100", "pageSize=3&page=2");
            Resp p = checked(contractPath(api), paged, 200);
            List<Long> window = expected.subList(Math.min(3, expected.size()), Math.min(6, expected.size()));
            assertThat(ids(p.body().get("items"))).as(c.get("id") + " 第 2 页").isEqualTo(window);
            assertThat(p.body().get("totalPages").asLong()).isEqualTo((expected.size() + 2) / 3);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Order(2)
    void topicRelatedContentMatchesLegacy() throws Exception {
        Map<String, String> before = snapshot();
        for (JsonNode t : golden.get("topics")) {
            String api = t.get("api").asString();
            Resp r = checked("/api/v1/topics/{id}", api, 200);
            // 旧版的关联视频/文章（仅已审核）；新版另加分类可见性：分类 3 下的视频 107、分类 23 下的文章 13 不出现
            assertThat(ids(r.body().get("relatedVideos"))).as(api + " 视频").isEqualTo(longs(t.get("videoIds")));
            assertThat(ids(r.body().get("relatedArticles"))).as(api + " 文章").isEqualTo(longs(t.get("articleIds")));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Order(3)
    void decodedMultiValueFieldsMatchLegacy() throws Exception {
        for (JsonNode d : golden.get("details")) {
            String api = d.get("api").asString();
            Resp r = checked(contractPath(api), api, 200);
            if (d.has("pages")) {
                List<String> expected = new ArrayList<>();
                d.get("pages").forEach(p -> expected.add(p.get("page").asInt() + "|" + str(p.get("title")) + "|"
                        + str(p.get("note")) + "|" + str(p.get("content"))));
                List<String> actual = new ArrayList<>();
                r.body().get("pages").forEach(p -> actual.add(p.get("page").asInt() + "|" + p.get("title").asString() + "|"
                        + p.get("note").asString() + "|" + p.get("content").asString()));
                assertThat(actual).as(api).isEqualTo(expected);
            }
            if (d.has("screenshots")) {
                List<String> expected = new ArrayList<>();
                d.get("screenshots").forEach(s -> expected.add(s.get("key").asInt() + "|" + str(s.get("value").get("name"))
                        + "|" + str(s.get("value").get("url"))));
                List<String> actual = new ArrayList<>();
                r.body().get("screenshots").forEach(s -> actual.add(s.get("index").asInt() + "|" + s.get("name").asString()
                        + "|" + s.get("url").asString()));
                assertThat(actual).as(api).isEqualTo(expected);
            }
            if (d.has("plots")) {
                List<String> expected = new ArrayList<>();
                d.get("plots").forEach(s -> expected.add(s.get("key").asInt() + "|" + str(s.get("value").get("name"))
                        + "|" + str(s.get("value").get("detail"))));
                List<String> actual = new ArrayList<>();
                r.body().get("items").forEach(s -> actual.add(s.get("index").asInt() + "|" + s.get("name").asString()
                        + "|" + s.get("detail").asString()));
                assertThat(actual).as(api).isEqualTo(expected);
            }
        }
    }

    // ------------------------------------------------------------------ 新版规则（不在旧版对照范围内）

    @Test
    @Order(4)
    void visibilityRulesOnRealData() throws Exception {
        // 分类树
        assertThat(ids(checked("/api/v1/article-categories", "/api/v1/article-categories", 200).body().get("items")))
                .containsExactly(21L, 25L);
        assertThat(ids(checked("/api/v1/actor-categories", "/api/v1/actor-categories", 200).body().get("items")))
                .containsExactly(31L);
        assertThat(ids(checked("/api/v1/website-categories", "/api/v1/website-categories", 200).body().get("items")))
                .containsExactly(41L);
        // 不可见分类：列表 404，详情 404
        for (String q : List.of("/api/v1/articles?categoryId=23", "/api/v1/articles?categoryId=26",
                "/api/v1/actors?categoryId=33", "/api/v1/websites?categoryId=43", "/api/v1/articles?categoryId=31")) {
            assertThat(checked(contractPath(q), q, 404).body().get("code").asString()).isEqualTo("CATEGORY_NOT_FOUND");
        }
        assertThat(checked("/api/v1/articles/{id}", "/api/v1/articles/13", 404).body().get("code").asString())
                .isEqualTo("ARTICLE_NOT_FOUND");
        assertThat(checked("/api/v1/articles/{id}", "/api/v1/articles/11", 404).body().get("code").asString())
                .isEqualTo("ARTICLE_NOT_FOUND");
        assertThat(checked("/api/v1/actors/{id}", "/api/v1/actors/13", 404).body().get("code").asString())
                .isEqualTo("ACTOR_NOT_FOUND");
        assertThat(checked("/api/v1/websites/{id}", "/api/v1/websites/13", 404).body().get("code").asString())
                .isEqualTo("WEBSITE_NOT_FOUND");
        assertThat(checked("/api/v1/topics/{id}", "/api/v1/topics/11", 404).body().get("code").asString())
                .isEqualTo("TOPIC_NOT_FOUND");
        assertThat(checked("/api/v1/roles/{id}", "/api/v1/roles/12", 404).body().get("code").asString())
                .isEqualTo("ROLE_NOT_FOUND");
        assertThat(checked("/api/v1/videos/{id}/plots", "/api/v1/videos/107/plots", 404).body().get("code").asString())
                .isEqualTo("VIDEO_NOT_FOUND");
        assertThat(checked("/api/v1/videos/{id}/plots", "/api/v1/videos/106/plots", 404).body().get("code").asString())
                .isEqualTo("VIDEO_NOT_FOUND");

        // 角色所属视频：101 可见；999 不存在 → id=0
        Resp roles = checked("/api/v1/roles", "/api/v1/roles?sort=id&order=asc&pageSize=100", 200);
        JsonNode items = roles.body().get("items");
        assertThat(items.get(0).get("video").get("id").asLong()).isEqualTo(101);
        assertThat(items.get(0).get("video").get("name").asString()).isEqualTo("视频101");
        assertThat(items.get(3).get("video").get("id").asLong()).isZero(); // role 4：role_rid=0
        assertThat(items.get(8).get("video").get("id").asLong()).isZero(); // role 9：role_rid=999
        Resp role = checked("/api/v1/roles/{id}", "/api/v1/roles/7", 200);
        assertThat(role.body().get("video").get("id").asLong()).isEqualTo(104);
        assertThat(role.body().get("content").asString()).isEqualTo("<p>角色</p>");

        // 详情字段
        Resp art = checked("/api/v1/articles/{id}", "/api/v1/articles/1", 200);
        assertThat(art.body().get("category").get("id").asInt()).isEqualTo(21);
        assertThat(art.body().get("score").decimalValue()).isEqualByComparingTo("10.5");
        Resp web = checked("/api/v1/websites/{id}", "/api/v1/websites/1", 200);
        assertThat(web.body().get("url").asString()).isEqualTo("https://site1.example.test/");
        assertThat(checked("/api/v1/actors/{id}", "/api/v1/actors/2", 200).body().get("category").get("parentId")
                .asInt()).isEqualTo(31);
    }

    /**
     * 最后执行：追加文章分类 21 下需要密码/积分的文章（会改变列表结果，所以排在旧版对照之后）。本测试的应用按默认配置运行，
     * user.art_points_type 为仓库值 1：只看 art_points（整篇收费，index/controller/Base.php:108-111，B05）；为 0 时只看
     * art_points_detail 的情形由 ArticlePointsTypeTest 覆盖。
     */
    @Test
    @Order(5)
    void articlePasswordAndPointsGates() throws Exception {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("INSERT INTO mac_art (art_id,type_id,art_name,art_status,art_pwd,art_points,art_points_detail,"
                    + "art_title,art_note,art_content) VALUES (15,21,'密码文章',1,'" + SENTINEL + "',0,0,'','','" + SENTINEL
                    + "'),(16,21,'按页积分',1,'',0,5,'','','<p>按页积分</p>'),(17,21,'双重',1,'x',3,0,'','','" + SENTINEL + "')");
        }
        Map<String, String> before = snapshot();
        assertThat(checked("/api/v1/articles/{id}", "/api/v1/articles/15", 403).body().get("code").asString())
                .isEqualTo("ARTICLE_PASSWORD_REQUIRED");
        // art_points_type=1 时只看 art_points：只有 art_points_detail 大于 0 的文章不需要积分（旧版直接显示）
        assertThat(checked("/api/v1/articles/{id}", "/api/v1/articles/16", 200).body().get("pages").get(0)
                .get("content").asString()).isEqualTo("<p>按页积分</p>");
        // 积分先于密码
        assertThat(checked("/api/v1/articles/{id}", "/api/v1/articles/17", 403).body().get("code").asString())
                .isEqualTo("ARTICLE_POINTS_REQUIRED");
        // 列表照常列出，只标记需要密码，不返回密码与积分
        Resp list = checked("/api/v1/articles", "/api/v1/articles?sort=id&order=desc&pageSize=3", 200);
        assertThat(ids(list.body().get("items"))).containsExactly(17L, 16L, 15L);
        assertThat(list.body().get("items").get(2).get("detailPasswordRequired").asBoolean()).isTrue();
        assertThat(list.body().get("items").get(1).get("detailPasswordRequired").asBoolean()).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }
}
