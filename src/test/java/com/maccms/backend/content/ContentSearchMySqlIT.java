package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
 * 契约 1.1.0 搜索/筛选的真实 MySQL 集成测试（./mvnw verify -Pmysql-it，MACC-18）。
 *
 * <p>与 {@link ContentApiMySqlIT} 相同的一次性库流程：外部管理账号新建 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入
 * application/install/sql/install.sql 表结构（MyISAM/utf8）→ 写入合成数据 → 真实 HTTP 请求 → 前后比对全部表的
 * CHECKSUM 与行数 → 只删除该库并确认已不存在。单独建库是因为 ContentApiMySqlIT 对其数据集的完整列表有逐项断言，
 * 搜索样本放进去会改变那些断言。
 *
 * <p>覆盖：每个条件与组合、%/_/!/\/逗号字面匹配、实体变体、utf8_general_ci 大小写不敏感、不可见/停用分类与未审核视频
 * 不出现、带条件分页无重复无遗漏、count 与列表一致、敏感字段不泄露、服务端执行时限与并发上限 → 503。
 * 不连接、不读写任何已有库（包括 maccms_dev）。
 */
@ExtendWith(OutputCaptureExtension.class)
class ContentSearchMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENTINEL = "SENTINEL";
    private static final String SECRET_PWD = "SECRETPWD";
    private static final String UNAVAILABLE =
            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂时不可用\",\"fieldErrors\":[]}";

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
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            seed(c, st);
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

    private static List<String> installStatements() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
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

    private static void type(Statement st, int id, String name, int sort, int mid, int pid, int status)
            throws SQLException {
        st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,"
                + "type_tpl,type_tpl_list,type_tpl_detail,type_extend) VALUES (" + id + ",'" + name + "','en" + id
                + "'," + sort + "," + mid + "," + pid + "," + status + ",'" + SENTINEL + "_TPL','','','{}')");
    }

    private record V(int id, int type, int status, String name, String sub, String en, String area, String lang,
                     String year, int level, String classes, long time, int hits) {
    }

    private static void vod(PreparedStatement ps, V v) throws SQLException {
        Object[] values = {v.id, v.type, v.status, v.name, v.sub, v.en, v.area, v.lang, v.year, v.level, v.classes,
                v.time, v.time - 100, v.hits, "",
                SENTINEL + "_PWDURL", SENTINEL + "P1", SENTINEL + "P2", SENTINEL + "_TPL", SENTINEL + "_REURL",
                SENTINEL + "_JUMP", SENTINEL + "_AUTHOR", "<p>正文" + v.id + "</p>", "第1集$" + SENTINEL + "_PLAY.m3u8",
                "第1集$" + SENTINEL + "_DOWN", SENTINEL + "_PLOT", SENTINEL + "_PLOTD"};
        for (int i = 0; i < values.length; i++) {
            ps.setObject(i + 1, values[i]);
        }
        ps.executeUpdate();
    }

    /**
     * 分类与游客权限同 ContentApiMySqlIT：可见 1←6,7；2←13。不可见：3 游客无列表位；4 停用；8 父分类 4 停用；
     * 14 自身停用；5 文章分类。视频样本见各行注释。
     */
    private static void seed(Connection c, Statement st) throws SQLException {
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

        List<V> rows = new ArrayList<>(List.of(
                new V(201, 1, 1, "合成影片甲", "", "hecheng-jia", "大陆", "国语", "2020", 9, "剧情,动作", 3000, 0),
                new V(202, 6, 1, "合成影片乙", "", "hecheng-yi", "大陆", "粤语", "2021", 0, "动作冒险", 3001, 0),
                new V(203, 7, 1, "百分比100%电影", "", "", "香港", "粤语", "2019", 0, "", 3002, 0),
                new V(204, 1, 1, "百分比100X电影", "", "", "香港", "", "2019", 0, "", 3003, 0),
                new V(205, 1, 1, "下划a_b片", "", "", "", "", "", 0, "", 3004, 0),
                new V(206, 1, 1, "下划axb片", "", "", "", "", "", 0, "", 3005, 0),
                new V(207, 1, 1, "反斜C:\\dir片", "", "", "", "", "", 0, "", 3006, 0),
                new V(208, 1, 1, "反斜C:dir片", "", "", "", "", "", 0, "", 3007, 0),
                new V(209, 1, 1, "叹号wow!片", "", "", "", "", "", 0, "", 3008, 0),
                new V(210, 1, 1, "叹号wow片", "", "", "", "", "", 0, "", 3009, 0),
                new V(211, 2, 1, "Tom &amp; Jerry", "", "", "", "", "", 0, "", 3010, 0),   // 以实体形式存库
                new V(212, 2, 1, "Tom & Jerry", "", "", "", "", "", 0, "", 3011, 0),       // 原样存库
                new V(213, 2, 1, "逗号,片", "", "", "", "", "", 0, "", 3012, 0),
                new V(214, 2, 1, "UPPER Case Show", "", "", "", "", "", 0, "", 3013, 0),
                new V(215, 13, 1, "某剧", "别名匹配", "", "", "", "", 0, "", 3014, 0),
                new V(216, 13, 1, "某片", "", "english-only", "", "", "", 0, "", 3015, 0),
                new V(217, 6, 0, "合成影片未审", "", "", "大陆", "国语", "2020", 9, "剧情", 9000, 0),   // 未审核
                new V(218, 3, 1, "合成影片隐藏", "", "", "大陆", "国语", "2020", 9, "剧情", 9001, 0),   // 无游客列表位
                new V(219, 4, 1, "合成影片停用", "", "", "大陆", "国语", "2020", 9, "剧情", 9002, 0),   // 分类停用
                new V(220, 8, 1, "合成影片番", "", "", "大陆", "国语", "2020", 9, "剧情", 9003, 0),     // 父分类停用
                new V(221, 13, 1, "合成影片丙", "", "", "大陆", "国语", "2020", 9, "悬疑剧情", 3016, 0),
                new V(222, 1, 1, "年份样本", "", "", "", "", "2020年", 0, "", 3017, 0),
                new V(223, 1, 1, "等级样本", "", "", "", "", "", 5, "", 3018, 0),
                new V(224, 14, 1, "合成影片港", "", "", "大陆", "国语", "2020", 9, "剧情", 9004, 0),    // 子分类停用
                new V(225, 5, 1, "合成影片资讯", "", "", "大陆", "国语", "2020", 9, "剧情", 9005, 0),   // 文章分类
                new V(226, 1, 1, "合成影片密", "", "", "台湾", "", "", 0, "", 3019, 0)));      // 有观看密码（见下）
        // 分页样本：30 行，vod_time 全部相同、hits 两两相同，检验并列时按 vod_id 稳定分页
        for (int i = 0; i < 30; i++) {
            rows.add(new V(230 + i, 6, 1, "分页样本" + i, "", "", "日本", "日语", "2018", 0, "", 5000, i / 2));
        }
        String sql = "INSERT INTO mac_vod (vod_id,type_id,vod_status,vod_name,vod_sub,vod_en,vod_area,vod_lang,"
                + "vod_year,vod_level,vod_class,vod_time,vod_time_add,vod_hits,vod_pwd,vod_pwd_url,vod_pwd_play,"
                + "vod_pwd_down,vod_tpl,vod_reurl,vod_jumpurl,vod_author,vod_content,vod_play_url,vod_down_url,"
                + "vod_plot_name,vod_plot_detail) VALUES (" + "?,".repeat(26) + "?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (V v : rows) {
                vod(ps, v);
            }
        }
        st.execute("UPDATE mac_vod SET vod_pwd='" + SECRET_PWD + "' WHERE vod_id=226");
    }

    // ------------------------------------------------------------------ 工具

    private record Resp(int status, JsonNode body, String raw) {
    }

    private static Resp get(String pathAndQuery) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), JSON.readTree(r.body()), r.body());
    }

    private static Resp checked(String query, int expectedStatus) throws Exception {
        Resp r = get("/api/v1/videos" + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as(query + " 状态码，响应: " + r.raw()).isEqualTo(expectedStatus);
        contract.assertMatches(r.body(), contract.responseSchema("/api/v1/videos", "get", expectedStatus));
        assertThat(r.raw()).as(query + " 不得泄露内部字段值").doesNotContain(SENTINEL).doesNotContain(SECRET_PWD);
        return r;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** 带 pageSize=100（一页取全）请求，断言 total 与列表条数一致，返回 id 列表。 */
    private static List<Long> ids(String query) throws Exception {
        Resp r = checked(query + (query.isEmpty() ? "" : "&") + "pageSize=100", 200);
        List<Long> out = new ArrayList<>();
        r.body().get("items").forEach(n -> out.add(n.get("id").asLong()));
        assertThat(r.body().get("total").asLong()).as(query + " count 与列表一致").isEqualTo(out.size());
        return out;
    }

    private static List<Long> idsAsc(String query) throws Exception {
        return ids(query + "&sort=id&order=asc");
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

    // ------------------------------------------------------------------ 测试

    @Test
    void searchAndFiltersOnRealMySqlHaveNoSideEffects() throws Exception {
        Map<String, String> before = snapshot();
        assertThat(before).containsKeys("mac_vod.checksum", "mac_vod_search.checksum", "mac_user.checksum");
        assertThat(before.get("mac_vod_search.rows")).isEqualTo("0");

        // 不带新参数：与 1.0.0 相同；旧站参数名（wd/class/tid/by）继续被忽略
        String all = get("/api/v1/videos?pageSize=100").raw();
        List<Long> visible = ids("");
        assertThat(visible).hasSize(50).doesNotContain(217L, 218L, 219L, 220L, 224L, 225L);
        for (String legacy : List.of("wd=" + enc("合成影片"), "class=" + enc("剧情"), "tid=1", "by=hits")) {
            assertThat(get("/api/v1/videos?pageSize=100&" + legacy).raw()).as(legacy).isEqualTo(all);
        }

        // keyword：vod_name/vod_sub/vod_en 包含匹配，只在可见范围内
        assertThat(idsAsc("keyword=" + enc("合成影片"))).containsExactly(201L, 202L, 221L, 226L);
        assertThat(idsAsc("keyword=" + enc("合成影片") + "&categoryId=1")).containsExactly(201L, 202L, 226L);
        assertThat(idsAsc("keyword=" + enc("合成影片") + "&categoryId=13")).containsExactly(221L);
        assertThat(idsAsc("keyword=" + enc("别名"))).as("vod_sub").containsExactly(215L);
        assertThat(idsAsc("keyword=ENGLISH")).as("vod_en，大小写不敏感").containsExactly(216L);
        assertThat(idsAsc("keyword=" + enc("upper case"))).as("utf8_general_ci").containsExactly(214L);
        assertThat(idsAsc("keyword=" + enc("不存在的关键字"))).isEmpty();
        Resp none = checked("keyword=" + enc("不存在的关键字"), 200);
        assertThat(none.body().get("total").asLong()).isZero();
        assertThat(none.body().get("totalPages").asLong()).isZero();

        // LIKE 字面语义：% _ ! \ 与逗号
        assertThat(idsAsc("keyword=" + enc("100%"))).containsExactly(203L);
        assertThat(idsAsc("keyword=100")).containsExactly(203L, 204L);
        assertThat(idsAsc("keyword=" + enc("a_b"))).containsExactly(205L);
        assertThat(idsAsc("keyword=" + enc("C:\\dir"))).containsExactly(207L);
        assertThat(idsAsc("keyword=" + enc("C:"))).containsExactly(207L, 208L);
        assertThat(idsAsc("keyword=" + enc("wow!"))).containsExactly(209L);
        assertThat(idsAsc("keyword=" + enc("号,片"))).containsExactly(213L);
        // 实体变体：原样与 mac_filter_xss 形式都命中；输入中的实体按字面匹配
        assertThat(idsAsc("keyword=" + enc("Tom & Jerry"))).containsExactly(211L, 212L);
        assertThat(idsAsc("keyword=" + enc("&amp;"))).containsExactly(211L);

        // 精确筛选
        assertThat(idsAsc("area=" + enc("大陆"))).containsExactly(201L, 202L, 221L);
        assertThat(idsAsc("area=" + enc("大陆") + "&lang=" + enc("国语"))).containsExactly(201L, 221L);
        assertThat(idsAsc("lang=" + enc("粤语"))).containsExactly(202L, 203L);
        assertThat(idsAsc("year=2020")).as("字符串比较，'2020年' 不命中").containsExactly(201L, 221L);
        assertThat(idsAsc("year=2021")).containsExactly(202L);
        assertThat(idsAsc("level=9")).containsExactly(201L, 221L);
        assertThat(idsAsc("level=09")).containsExactly(201L, 221L);
        assertThat(idsAsc("level=5")).containsExactly(223L);
        // classes：包含匹配
        assertThat(idsAsc("classes=" + enc("剧情"))).containsExactly(201L, 221L);
        assertThat(idsAsc("classes=" + enc("动作"))).containsExactly(201L, 202L);

        // 契约 1.3.0（MACC-28 Q1）：area/lang 逗号多值 = IN（并集），classes 逗号多值 = 各项包含匹配 OR；保留空项，全角逗号是字面字符
        assertThat(idsAsc("area=" + enc("大陆,香港"))).as("并集").containsExactly(201L, 202L, 203L, 204L, 221L);
        assertThat(idsAsc("area=" + enc("香港,"))).as("尾逗号的空项命中地区为空的行")
                .containsExactly(203L, 204L, 205L, 206L, 207L, 208L, 209L, 210L, 211L, 212L, 213L, 214L, 215L, 216L,
                        222L, 223L);
        assertThat(idsAsc("area=" + enc(","))).as("只有空项").containsExactly(205L, 206L, 207L, 208L, 209L, 210L,
                211L, 212L, 213L, 214L, 215L, 216L, 222L, 223L);
        assertThat(idsAsc("area=" + enc("大陆，香港"))).as("全角逗号不拆分，整串无命中").isEmpty();
        assertThat(idsAsc("area=" + enc("大陆, 香港"))).as("各项不裁剪：' 香港' 不命中").containsExactly(201L, 202L, 221L);
        assertThat(idsAsc("lang=" + enc("国语,粤语"))).containsExactly(201L, 202L, 203L, 221L);
        assertThat(idsAsc("lang=" + enc("粤语") + "&area=" + enc("大陆,香港"))).containsExactly(202L, 203L);
        assertThat(idsAsc("classes=" + enc("剧情,冒险"))).containsExactly(201L, 202L, 221L);
        assertThat(idsAsc("classes=" + enc("悬疑,冒险,不存在"))).containsExactly(202L, 221L);
        assertThat(checked("classes=" + enc("剧情,") + "&pageSize=100", 200).body().get("total").asLong())
                .as("空项为 %%，命中全部可见行（与旧 mac_like_arr 相同）").isEqualTo(50);
        assertThat(idsAsc("classes=" + enc("剧情，动作"))).as("全角逗号不拆分").isEmpty();
        assertThat(idsAsc("classes=" + enc("a_b,100%"))).as("各项 % _ 按字面").isEmpty();
        // 单值回归：与 1.1.0 相同
        assertThat(idsAsc("area=" + enc("香港"))).containsExactly(203L, 204L);
        assertThat(idsAsc("classes=" + enc("剧情"))).containsExactly(201L, 221L);
        // 多值条件下分页：逐页拼接 = 一次取全，total 与列表一致
        for (String q : List.of("area=" + enc("日本,台湾"), "lang=" + enc("日语,") + "&area=" + enc("日本,台湾"),
                "classes=" + enc(",剧情") + "&area=" + enc("日本,台湾"))) {
            List<Long> full = ids(q);
            assertThat(full).as(q).hasSize(31).contains(226L);
            List<Long> paged = new ArrayList<>();
            for (int p = 1; p <= 5; p++) {
                Resp page = checked(q + "&pageSize=7&page=" + p, 200);
                assertThat(page.body().get("total").asLong()).as(q + " total（count 与列表条件一致）").isEqualTo(31);
                page.body().get("items").forEach(n -> paged.add(n.get("id").asLong()));
            }
            assertThat(paged).as(q).containsExactlyElementsOf(full);
        }

        // 组合：全部 AND，再按分类收窄
        String combo = "keyword=" + enc("合成") + "&area=" + enc("大陆") + "&lang=" + enc("国语")
                + "&year=2020&level=9&classes=" + enc("剧情");
        assertThat(idsAsc(combo)).containsExactly(201L, 221L);
        assertThat(idsAsc(combo + "&categoryId=2")).containsExactly(221L);
        assertThat(idsAsc(combo + "&categoryId=6")).isEmpty();
        for (int hidden : new int[]{3, 4, 5, 8, 14}) {
            assertThat(checked(combo + "&categoryId=" + hidden, 404).body().get("code").asString())
                    .isEqualTo("CATEGORY_NOT_FOUND");
        }

        // 列表项：有观看密码只给布尔值
        Resp locked = checked("keyword=" + enc("合成影片密"), 200);
        assertThat(locked.body().get("items").get(0).get("detailPasswordRequired").asBoolean()).isTrue();

        // 带条件分页：逐页拼接 = 一次取全，无重复无遗漏；并列排序值按 vod_id 同向
        for (String sort : List.of("", "&sort=hits", "&sort=hits&order=asc", "&sort=time&order=asc")) {
            String q = "area=" + enc("日本") + sort;
            List<Long> full = ids(q);
            assertThat(full).hasSize(30);
            List<Long> paged = new ArrayList<>();
            for (int p = 1; p <= 5; p++) {
                Resp page = checked(q + "&pageSize=7&page=" + p, 200);
                assertThat(page.body().get("total").asLong()).isEqualTo(30);
                assertThat(page.body().get("totalPages").asLong()).isEqualTo(5);
                page.body().get("items").forEach(n -> paged.add(n.get("id").asLong()));
            }
            assertThat(paged).as(q).containsExactlyElementsOf(full);
            assertThat(new HashSet<>(paged)).hasSize(30);
            Resp beyond = checked(q + "&pageSize=7&page=6", 200);
            assertThat(beyond.body().get("items")).isEmpty();
            assertThat(beyond.body().get("total").asLong()).isEqualTo(30);
        }
        assertThat(ids("area=" + enc("日本"))).as("默认 time desc，并列按 vod_id desc").startsWith(259L, 258L, 257L);

        // 参数错误（真实 Tomcat）：非法 UTF-8、首尾空白（含多值的末项）、超长（整个原始值）、重复
        for (String[] bad : new String[][]{{"keyword=%FF", "keyword"}, {"keyword=%E4%B8", "keyword"},
                {"area=%FF", "area"}, {"area=a,%20", "area"}, {"keyword=%20x", "keyword"}, {"area=&area=", "area"},
                {"year=2020-202", "year"}, {"level=00000000009", "level"}, {"classes=" + enc(",".repeat(33)), "classes"},
                {"lang=" + enc("a,b,c,d,e,f"), "lang"}, {"classes=a,b&classes=c", "classes"}}) {
            Resp r = checked(bad[0], 400);
            assertThat(r.body().get("fieldErrors").get(0).get("field").asString()).as(bad[0]).isEqualTo(bad[1]);
        }

        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    @Test
    void statementTimeLimitAndConcurrencyLimitReturn503(CapturedOutput output) throws Exception {
        Map<String, String> before = snapshot();
        try (Connection locker = admin(dbName); Statement st = locker.createStatement()) {
            // 其他会话持有 mac_vod 写锁（只加锁、不改数据），带条件的 SELECT 会一直等锁，直到服务端执行时限到期
            st.execute("LOCK TABLES mac_vod WRITE");
            try {
                long t0 = System.nanoTime();
                Resp r = get("/api/v1/videos?area=" + enc("大陆"));
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                System.out.println("[mysql-it] filtered request under table lock: HTTP " + r.status() + " after "
                        + ms + " ms");
                assertThat(r.status()).isEqualTo(503);
                assertThat(r.raw()).isEqualTo(UNAVAILABLE);
                // 3000 ms 的服务端时限触发，而不是 10 s 的 JDBC 兜底超时
                assertThat(ms).isBetween(2_500L, 8_000L);
            } finally {
                st.execute("UNLOCK TABLES");
            }
        }
        assertThat(output.getAll()).contains("database statement timeout").doesNotContain("大陆")
                .doesNotContain("MAX_EXECUTION_TIME");

        // 契约 1.3.0（MACC-28 Q1）：逗号多值同样受服务端时限约束；classes 多值/空项占用并发许可，超时后归还
        try (Connection locker = admin(dbName); Statement st = locker.createStatement()) {
            st.execute("LOCK TABLES mac_vod WRITE");
            try {
                for (String q : List.of("area=" + enc("大陆,香港"), "classes=" + enc("剧情,动作"), "classes=" + enc(","))) {
                    long t0 = System.nanoTime();
                    Resp r = get("/api/v1/videos?" + q);
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    System.out.println("[mysql-it] multi-value request under table lock: HTTP " + r.status()
                            + " after " + ms + " ms");
                    assertThat(r.status()).as(q).isEqualTo(503);
                    assertThat(r.raw()).isEqualTo(UNAVAILABLE);
                    assertThat(ms).isBetween(2_500L, 8_000L);
                }
            } finally {
                st.execute("UNLOCK TABLES");
            }
        }
        assertThat(output.getAll()).doesNotContain("香港").doesNotContain("剧情");

        try (Connection locker = admin(dbName); Statement st = locker.createStatement()) {
            st.execute("LOCK TABLES mac_vod WRITE");
            List<CompletableFuture<HttpResponse<String>>> blocked = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    blocked.add(HTTP.sendAsync(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/videos?keyword="
                            + enc("合成影片"))).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                }
                waitForWaitingQueries(locker, 2);
                // 并发上限 2 已占满：第 3 个包含匹配请求立即 503，不排队（classes 多值、只有空项同样占许可）
                for (String q : List.of("classes=" + enc("剧情"), "classes=" + enc("剧情,动作"), "classes=" + enc(","))) {
                    long t0 = System.nanoTime();
                    Resp third = get("/api/v1/videos?" + q);
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    assertThat(third.status()).as(q).isEqualTo(503);
                    assertThat(third.raw()).isEqualTo(UNAVAILABLE);
                    assertThat(ms).isLessThan(2_000L);
                }
            } finally {
                st.execute("UNLOCK TABLES");
            }
            for (CompletableFuture<HttpResponse<String>> f : blocked) {
                HttpResponse<String> r = f.get(10, TimeUnit.SECONDS);
                assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
                assertThat(JSON.readTree(r.body()).get("total").asLong()).isEqualTo(4);
            }
        }
        // 许可已释放：之后的搜索恢复正常
        assertThat(idsAsc("keyword=" + enc("合成影片"))).containsExactly(201L, 202L, 221L, 226L);
        assertThat(idsAsc("classes=" + enc("剧情,冒险"))).containsExactly(201L, 202L, 221L);
        assertThat(output.getAll()).contains("content search rejected: concurrency limit reached");
        assertThat(snapshot()).isEqualTo(before);
    }

    private static void waitForWaitingQueries(Connection c, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.processlist "
                + "WHERE db = ? AND id <> CONNECTION_ID() AND info LIKE '%MAX_EXECUTION_TIME%'")) {
            ps.setString(1, dbName);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= expected) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("等待中的搜索语句未达到 " + expected + " 个");
    }
}
