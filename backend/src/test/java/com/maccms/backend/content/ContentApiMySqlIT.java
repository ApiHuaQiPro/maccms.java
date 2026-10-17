package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 MySQL 集成测试（./mvnw verify -Pmysql-it）。
 *
 * <p>流程：用外部提供的管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入旧安装脚本
 * application/install/sql/install.sql 的表结构（MyISAM/utf8，与旧系统一致）→ 写入合成数据 →
 * 以真实 HTTP 启动应用逐项请求 → 校验结果、契约、无字段泄露、全部旧表无变化、连接只读 → 删除该一次性库。
 *
 * <p>不连接、不读写任何已有库（包括 maccms_dev）；库名带固定前缀且创建时不使用 IF NOT EXISTS，同名库存在会直接失败。
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（不含库名，如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 */
class ContentApiMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENTINEL = "SENTINEL";
    private static final String SECRET_PWD = "SECRETPWD";

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    /** 导入 install.sql 后的表结构指纹，删库前再比对一次（B02 表结构护栏）。 */
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;
    private static OpenApiContract contract;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);

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
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            seed(st);
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }

        String appUrl = jdbcUrlFor(dbName);
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + appUrl,
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        // 密码走系统属性而不是命令行参数，避免出现在进程参数或启动日志中
        System.setProperty("spring.datasource.password", adminPassword);
        app = new SpringApplicationBuilder(MaccmsBackendApplication.class).run(args.toArray(String[]::new));
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
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
                }
                assertThat(schemaDiff).as("B02 表结构护栏：测试结束时表结构与导入 install.sql 时一致").isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 当前表结构与导入时的差异；读取失败也记为差异（不影响随后删库）。 */
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

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 backend/README.md");
        }
        return v;
    }

    private static String jdbcUrlFor(String db) {
        String base = adminUrl;
        int q = base.indexOf('?');
        String params = q >= 0 ? base.substring(q) : "";
        String path = q >= 0 ? base.substring(0, q) : base;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        String extra = params.isEmpty() ? "?characterEncoding=UTF-8" : params;
        return path + (db == null ? "" : db) + extra;
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static List<String> installStatements() throws IOException {
        String text = Files.readString(INSTALL_SQL, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
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

    private static void type(Statement st, int id, String name, int sort, int mid, int pid, int status) throws SQLException {
        st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,"
                + "type_tpl,type_tpl_list,type_tpl_detail,type_extend) VALUES (" + id + ",'" + name + "','en" + id
                + "'," + sort + "," + mid + "," + pid + "," + status + ",'" + SENTINEL + "_TPL','','',"
                + "'{\"class\":\"喜剧,爱情\",\"area\":\"大陆\",\"year\":\"2024\"}')");
    }

    private static void vod(Statement st, int id, int typeId, int status, long time, String pwd, int copyright,
                            int hits) throws SQLException {
        st.execute("INSERT INTO mac_vod (vod_id,type_id,type_id_1,group_id,vod_name,vod_status,vod_time,vod_time_add,"
                + "vod_pwd,vod_pwd_url,vod_pwd_play,vod_pwd_down,vod_copyright,vod_hits,vod_score,vod_points,"
                + "vod_points_play,vod_points_down,vod_trysee,vod_tpl,vod_reurl,vod_jumpurl,vod_author,vod_lock,"
                + "vod_content,vod_play_from,vod_play_url,vod_down_from,vod_down_url,vod_plot_name,vod_plot_detail,"
                + "vod_pic_screenshot,vod_rel_vod) VALUES ("
                + id + "," + typeId + ",0,3,'视频" + id + "'," + status + "," + time + "," + (time - 100) + ",'" + pwd
                + "','" + SENTINEL + "_PWDURL','" + SENTINEL + "P1','" + SENTINEL + "P2'," + copyright
                + "," + hits + ",8.5,7,8,9,5,'" + SENTINEL + "_TPL','" + SENTINEL + "_REURL','" + SENTINEL
                + "_JUMP','" + SENTINEL + "_AUTHOR',1,'<p>正文" + id + "</p>','dplayer','第1集$" + SENTINEL
                + "_PLAY.m3u8','xunlei','第1集$" + SENTINEL + "_DOWN','" + SENTINEL + "_PLOT','" + SENTINEL
                + "_PLOTD','" + SENTINEL + "_SHOT','" + SENTINEL + "_REL')");
    }

    /**
     * 可见分类：1(电影)←6,7；2(电视剧)←13。
     * 不可见：3 游客无列表位；4 停用；8 父分类 4 停用；14 自身停用；5 是文章分类(type_mid=2)。
     */
    private static void seed(Statement st) throws SQLException {
        st.execute("DELETE FROM mac_type");
        type(st, 1, "电影", 1, 1, 0, 1);
        type(st, 6, "动作片", 1, 1, 1, 1);
        type(st, 7, "喜剧片", 2, 1, 1, 1);
        type(st, 2, "电视剧", 2, 1, 0, 1);
        type(st, 13, "国产剧", 1, 1, 2, 1);
        type(st, 3, "综艺", 3, 1, 0, 1);
        type(st, 4, "动漫", 4, 1, 0, 0);
        type(st, 8, "番剧", 1, 1, 4, 1);
        type(st, 14, "港剧", 2, 1, 2, 0);
        type(st, 5, "资讯", 5, 2, 0, 1);
        String allBits = "{\"1\":\"1\",\"2\":\"2\",\"3\":\"3\",\"4\":\"4\",\"5\":\"5\"}";
        st.execute("UPDATE mac_group SET group_type=',1,6,7,2,13,3,4,8,14,5,', group_popedom='{"
                + "\"1\":" + allBits + ",\"6\":" + allBits + ",\"7\":{\"1\":\"1\"},\"2\":" + allBits
                + ",\"13\":" + allBits + ",\"3\":{\"2\":\"2\"},\"4\":" + allBits + ",\"8\":" + allBits
                + ",\"14\":" + allBits + ",\"5\":" + allBits + "}' WHERE group_id=1");
        vod(st, 101, 1, 1, 1000, "", 0, 0);
        vod(st, 102, 6, 1, 1000, "", 0, 0);
        vod(st, 103, 7, 1, 1000, "", 0, 0);
        vod(st, 104, 2, 1, 2000, "", 0, 0);
        vod(st, 105, 13, 1, 500, "", 0, 0);
        vod(st, 106, 6, 0, 9000, "", 0, 0);       // 未审核
        vod(st, 107, 3, 1, 9000, "", 0, 0);       // 分类无游客列表权限
        vod(st, 108, 4, 1, 9000, "", 0, 0);       // 分类停用
        vod(st, 109, 8, 1, 9000, "", 0, 0);       // 父分类停用
        vod(st, 110, 1, 1, 1500, SECRET_PWD, 0, 0); // 有观看密码
        vod(st, 111, 1, 1, 1500, "", 1, 0);       // 版权
        vod(st, 112, 7, 1, 300, "", 0, 0);        // 游客无详情位
        vod(st, 113, 5, 1, 9000, "", 0, 0);       // 文章分类下
        vod(st, 114, 14, 1, 9000, "", 0, 0);      // 分类停用（子分类）
    }

    // ------------------------------------------------------------------ 工具

    private record Resp(int status, JsonNode body, String raw) {
    }

    private static Resp get(String pathAndQuery) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/");
        return new Resp(r.statusCode(), JSON.readTree(r.body()), r.body());
    }

    private static Resp checked(String contractPath, String pathAndQuery, int expectedStatus) throws Exception {
        Resp r = get(pathAndQuery);
        assertThat(r.status()).as(pathAndQuery + " 状态码，响应: " + r.raw()).isEqualTo(expectedStatus);
        contract.assertMatches(r.body(), contract.responseSchema(contractPath, "get", expectedStatus));
        assertThat(r.raw()).as(pathAndQuery + " 不得泄露内部字段值").doesNotContain(SENTINEL).doesNotContain(SECRET_PWD);
        return r;
    }

    private static List<Long> ids(Resp r) {
        List<Long> out = new ArrayList<>();
        r.body().get("items").forEach(n -> out.add(n.get("id").asLong()));
        return out;
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

    // ------------------------------------------------------------------ 测试

    @Test
    void endToEndReadsMatchLegacyRulesAndHaveNoSideEffects() throws Exception {
        Map<String, String> before = snapshot();
        assertThat(before).containsKeys("mac_vod.checksum", "mac_user.checksum", "mac_vod_search.checksum",
                "mac_type.checksum", "mac_group.checksum");

        // 分类树
        Resp cats = checked("/api/v1/video-categories", "/api/v1/video-categories", 200);
        JsonNode items = cats.body().get("items");
        assertThat(items).extracting(n -> n.get("id").asInt()).containsExactly(1, 2);
        assertThat(items.get(0).get("children")).extracting(n -> n.get("id").asInt()).containsExactly(6, 7);
        assertThat(items.get(1).get("children")).extracting(n -> n.get("id").asInt()).containsExactly(13);
        assertThat(items.get(0).get("name").asString()).isEqualTo("电影");
        assertThat(items.get(0).get("extend").get("classes").asString()).isEqualTo("喜剧,爱情");
        assertThat(items.get(0).get("extend").get("lang").asString()).isEmpty();

        // 默认列表：vod_time DESC, vod_id DESC
        Resp all = checked("/api/v1/videos", "/api/v1/videos", 200);
        assertThat(ids(all)).containsExactly(104L, 111L, 110L, 103L, 102L, 101L, 105L, 112L);
        assertThat(all.body().get("total").asLong()).isEqualTo(8);
        assertThat(all.body().get("items").get(2).get("detailPasswordRequired").asBoolean()).isTrue();
        assertThat(all.body().get("items").get(0).get("detailPasswordRequired").asBoolean()).isFalse();
        assertThat(all.body().get("items").get(0).get("score").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(all.body().get("items").get(0).get("category").get("name").asString()).isEqualTo("电视剧");

        // 分页稳定：逐页拼接与一次取全量一致，且无重复
        List<Long> paged = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            Resp page = checked("/api/v1/videos", "/api/v1/videos?pageSize=3&page=" + p, 200);
            assertThat(page.body().get("totalPages").asLong()).isEqualTo(3);
            paged.addAll(ids(page));
        }
        assertThat(paged).containsExactlyElementsOf(ids(all));
        Resp beyond = checked("/api/v1/videos", "/api/v1/videos?pageSize=3&page=4", 200);
        assertThat(beyond.body().get("items")).isEmpty();
        assertThat(beyond.body().get("total").asLong()).isEqualTo(8);

        // 排序白名单与并列时按 id 同向
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?sort=id&order=asc", 200)))
                .containsExactly(101L, 102L, 103L, 104L, 105L, 110L, 111L, 112L);
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?sort=hits", 200)))
                .containsExactly(112L, 111L, 110L, 105L, 104L, 103L, 102L, 101L);
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?sort=time_add&order=asc", 200)))
                .containsExactly(112L, 105L, 101L, 102L, 103L, 110L, 111L, 104L);

        // 按分类：父分类含直接子分类
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?categoryId=1", 200)))
                .containsExactly(111L, 110L, 103L, 102L, 101L, 112L);
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?categoryId=6", 200))).containsExactly(102L);
        assertThat(ids(checked("/api/v1/videos", "/api/v1/videos?categoryId=13", 200))).containsExactly(105L);
        for (int hidden : new int[]{3, 4, 5, 8, 14, 999}) {
            Resp r = checked("/api/v1/videos", "/api/v1/videos?categoryId=" + hidden, 404);
            assertThat(r.body().get("code").asString()).isEqualTo("CATEGORY_NOT_FOUND");
        }

        // 非法参数
        for (String q : List.of("page=0", "pageSize=101", "sort=rnd", "sort=vod_id", "order=random",
                "categoryId=1%20OR%201=1", "page=1001")) {
            Resp r = checked("/api/v1/videos", "/api/v1/videos?" + q, 400);
            assertThat(r.body().get("code").asString()).isEqualTo("INVALID_PARAMETER");
        }

        // 详情
        Resp d = checked("/api/v1/videos/{id}", "/api/v1/videos/101", 200);
        assertThat(d.body().get("name").asString()).isEqualTo("视频101");
        assertThat(d.body().get("content").asString()).isEqualTo("<p>正文101</p>");
        assertThat(d.body().get("category").get("id").asInt()).isEqualTo(1);
        assertThat(checked("/api/v1/videos/{id}", "/api/v1/videos/102", 200).body().get("category").get("parentId")
                .asInt()).isEqualTo(1);
        for (int hidden : new int[]{106, 107, 108, 109, 112, 113, 114, 999}) {
            Resp r = checked("/api/v1/videos/{id}", "/api/v1/videos/" + hidden, 404);
            assertThat(r.body().get("code").asString()).as("video " + hidden).isEqualTo("VIDEO_NOT_FOUND");
        }
        assertThat(checked("/api/v1/videos/{id}", "/api/v1/videos/110", 403).body().get("code").asString())
                .isEqualTo("VIDEO_PASSWORD_REQUIRED");
        assertThat(checked("/api/v1/videos/{id}", "/api/v1/videos/111", 403).body().get("code").asString())
                .isEqualTo("VIDEO_COPYRIGHT_RESTRICTED");
        assertThat(checked("/api/v1/videos/{id}", "/api/v1/videos/abc", 400).body().get("code").asString())
                .isEqualTo("INVALID_PARAMETER");

        // 健康检查：只有整体状态
        Resp h = get("/actuator/health");
        assertThat(h.status()).isEqualTo(200);
        contract.assertMatches(h.body(), contract.responseSchema("/actuator/health", "get", 200));
        assertThat(h.body().get("status").asString()).isEqualTo("UP");
        assertThat(get("/actuator/env").status()).isEqualTo(404);
        assertThat(get("/actuator/info").status()).isEqualTo(404);

        // 所有请求结束后，一次性库内所有表（含 mac_vod 计数、mac_vod_search、mac_user）完全未变
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void applicationConnectionsAreReadOnlyAtServerLevel() throws Exception {
        DataSource ds = app.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(c.isReadOnly()).isTrue();
            try (ResultSet rs = st.executeQuery("SELECT @@session.transaction_read_only")) {
                rs.next();
                assertThat(rs.getInt(1)).as("会话级 READ ONLY").isEqualTo(1);
            }
            // 第一层：驱动在客户端拒绝
            assertThatThrownBy(() -> st.executeUpdate("UPDATE mac_vod SET vod_hits = vod_hits + 1 WHERE vod_id = 101"))
                    .isInstanceOf(SQLException.class);
            // 第二层：绕过驱动的语句识别（PREPARE/EXECUTE），由 MySQL 服务端按会话 READ ONLY 拒绝，MyISAM 表同样适用
            // （MySQL 5.7 在 PREPARE 阶段即返回 1792）
            assertThatThrownBy(() -> {
                st.execute("PREPARE ro_probe FROM 'UPDATE mac_vod SET vod_hits = vod_hits + 1 WHERE vod_id = 101'");
                st.execute("EXECUTE ro_probe");
            }).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(1792));
        }
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT vod_hits FROM mac_vod WHERE vod_id = 101")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
