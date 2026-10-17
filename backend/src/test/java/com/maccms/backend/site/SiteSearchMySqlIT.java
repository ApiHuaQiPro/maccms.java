package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * SSR 搜索页（MACC-20）的真实 MySQL 集成测试（./mvnw verify -Pmysql-it）。流程同 ContentSearchMySqlIT：外部管理账号新建
 * 一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 application/install/sql/install.sql 表结构（MyISAM/utf8）→ 写入合成数据 →
 * 启动完整应用（site + 搜索页 + 包含匹配开启）→ 真实 HTTP 请求 → 前后比对全部表的 CHECKSUM 与行数 → 只删除该库并确认已不存在。
 *
 * <p>覆盖：关键字/精确筛选/字面 % 匹配只在游客可见范围内、分页与越界、候选值来自 type_extend、400/404 页、页面不含播放地址、
 * 观看密码等内部字段，日志不含关键字。不连接、不读写任何已有库（包括 maccms_dev）。
 */
@ExtendWith(OutputCaptureExtension.class)
class SiteSearchMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String SENTINEL = "SENTINEL";
    private static final String SECRET_PWD = "SECRETPWD";
    private static final String SEARCH = "/index.php/vod/search.html";

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    /** 导入 install.sql 后的表结构指纹，删库前再比对一次（B02 表结构护栏）。 */
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static String baseUrl;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
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
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/"));
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

    private static void type(PreparedStatement ps, int id, String name, int sort, int pid, int status, String extend)
            throws SQLException {
        Object[] values = {id, name, "en" + id, sort, 1, pid, status, SENTINEL + "_TPL", "", "", extend};
        for (int i = 0; i < values.length; i++) {
            ps.setObject(i + 1, values[i]);
        }
        ps.executeUpdate();
    }

    private static void vod(PreparedStatement ps, int id, int type, int status, String name, String area, String lang,
                            String year, String classes, long time) throws SQLException {
        Object[] values = {id, type, status, name, "", "", area, lang, year, 0, classes, time, time - 100, 0, "",
                SENTINEL + "_PWDURL", SENTINEL + "P1", SENTINEL + "P2", SENTINEL + "_TPL", SENTINEL + "_REURL",
                SENTINEL + "_JUMP", SENTINEL + "_AUTHOR", "<p>正文" + id + "</p>", "第1集$" + SENTINEL + "_PLAY.m3u8",
                "第1集$" + SENTINEL + "_DOWN", SENTINEL + "_PLOT", SENTINEL + "_PLOTD"};
        for (int i = 0; i < values.length; i++) {
            ps.setObject(i + 1, values[i]);
        }
        ps.executeUpdate();
    }

    /** 可见：1 电影（←6 动作片）、2 电视剧。不可见：3 游客无列表位、4 停用。type_extend 给出筛选候选值。 */
    private static void seed(Connection c, Statement st) throws SQLException {
        st.execute("DELETE FROM mac_type");
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,"
                + "type_mid,type_pid,type_status,type_tpl,type_tpl_list,type_tpl_detail,type_extend) VALUES "
                + "(?,?,?,?,?,?,?,?,?,?,?)")) {
            type(ps, 1, "电影", 1, 0, 1, "{\"class\":\"剧情,动作\",\"area\":\"大陆,香港,,0\",\"lang\":\"国语,粤语\","
                    + "\"year\":\"2021,2020\"}");
            type(ps, 6, "动作片", 1, 1, 1, "{\"area\":\"大陆,台湾\",\"year\":\"2019\"}");
            type(ps, 2, "电视剧", 2, 0, 1, "{\"area\":\"日本\",\"lang\":\"日语\"}");
            type(ps, 3, "综艺", 3, 0, 1, "{\"area\":\"隐藏地区\"}");
            type(ps, 4, "动漫", 4, 0, 0, "{\"area\":\"停用地区\"}");
        }
        String allBits = "{\"1\":\"1\",\"2\":\"2\",\"3\":\"3\",\"4\":\"4\",\"5\":\"5\"}";
        st.execute("UPDATE mac_group SET group_type=',1,6,2,3,4,', group_popedom='{\"1\":" + allBits + ",\"6\":"
                + allBits + ",\"2\":" + allBits + ",\"3\":{\"2\":\"2\"},\"4\":" + allBits + "}' WHERE group_id=1");
        String sql = "INSERT INTO mac_vod (vod_id,type_id,vod_status,vod_name,vod_sub,vod_en,vod_area,vod_lang,"
                + "vod_year,vod_level,vod_class,vod_time,vod_time_add,vod_hits,vod_pwd,vod_pwd_url,vod_pwd_play,"
                + "vod_pwd_down,vod_tpl,vod_reurl,vod_jumpurl,vod_author,vod_content,vod_play_url,vod_down_url,"
                + "vod_plot_name,vod_plot_detail) VALUES (" + "?,".repeat(26) + "?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            vod(ps, 301, 1, 1, "合成影片甲", "大陆", "国语", "2020", "剧情,动作", 4000);
            vod(ps, 302, 6, 1, "合成影片乙", "香港", "粤语", "2021", "动作", 4001);
            vod(ps, 303, 2, 1, "合成影片丙 100%", "日本", "日语", "2020", "", 4002);
            vod(ps, 304, 2, 1, "合成影片丁 100X", "日本", "日语", "2020", "", 4003);
            vod(ps, 305, 6, 0, "合成影片未审", "大陆", "国语", "2020", "剧情", 9000);
            vod(ps, 306, 3, 1, "合成影片隐藏", "大陆", "国语", "2020", "剧情", 9001);
            vod(ps, 307, 4, 1, "合成影片停用", "大陆", "国语", "2020", "剧情", 9002);
            for (int i = 0; i < 25; i++) {
                vod(ps, 320 + i, 1, 1, "分页样本" + i, "大陆", "国语", "2018", "", 5000 + i);
            }
        }
        st.execute("UPDATE mac_vod SET vod_pwd='" + SECRET_PWD + "' WHERE vod_id=301");
    }

    // ------------------------------------------------------------------ 工具

    private record Page(int status, String body, Document doc, Map<String, List<String>> headers) {
        List<Long> ids() {
            List<Long> out = new ArrayList<>();
            doc.select(".video-grid a.video-card__link").forEach(a -> {
                String href = a.attr("href");
                out.add(Long.parseLong(href.replaceAll(".*/id/([0-9]+)\\.html$", "$1")));
            });
            return out;
        }

        String header(String name) {
            List<String> v = headers.get(name);
            return v == null ? null : v.getFirst();
        }
    }

    private static Page get(String pathAndQuery) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Page p = new Page(r.statusCode(), r.body(), Jsoup.parse(r.body()), r.headers().map());
        assertThat(p.body()).as(pathAndQuery + " 不得泄露内部字段值").doesNotContain(SENTINEL).doesNotContain(SECRET_PWD);
        if (p.status() != 404 || p.body().contains("<html")) {
            assertThat(p.header("cache-control")).as(pathAndQuery).isEqualTo("no-store");
            assertThat(p.header("x-robots-tag")).as(pathAndQuery).isEqualTo("noindex");
        }
        return p;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
    void searchPagesOnRealMySqlHaveNoSideEffects(CapturedOutput output) throws Exception {
        Map<String, String> before = snapshot();
        assertThat(before).containsKeys("mac_vod.checksum", "mac_vod_search.checksum", "mac_type.checksum");
        assertThat(before.get("mac_vod_search.rows")).isEqualTo("0");

        // 无条件：不查库也能显示
        Page empty = get(SEARCH);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.doc().select(".search-page__hint")).hasSize(1);

        // 关键字：只在游客可见范围内（未审核、无列表权限、停用分类的不出现），默认 time desc
        Page kw = get(SEARCH + "?wd=" + enc("合成影片"));
        assertThat(kw.status()).isEqualTo(200);
        assertThat(kw.ids()).containsExactly(304L, 303L, 302L, 301L);
        assertThat(kw.doc().selectFirst(".search-summary").text()).isEqualTo("共 4 条");
        assertThat(kw.doc().select("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/vod/search.html?wd=" + "%E5%90%88%E6%88%90%E5%BD%B1%E7%89%87");
        // 候选值来自可见分类的 type_extend（跳过空项、'0'；不可见分类的值不出现）
        assertThat(kw.doc().select("ul[aria-labelledby=search-filter-area] a").eachText())
                .containsExactly("全部", "大陆", "香港", "台湾", "日本");
        assertThat(kw.doc().select("ul[aria-labelledby=search-filter-class] a").eachText())
                .containsExactly("全部", "剧情", "动作");
        assertThat(kw.body()).doesNotContain("隐藏地区").doesNotContain("停用地区").doesNotContain("合成影片隐藏");

        // 精确筛选 + 排序；% 按字面匹配（单次解码：%25 → %）
        assertThat(get(SEARCH + "?area=" + enc("香港") + "&wd=" + enc("合成")).ids()).containsExactly(302L);
        assertThat(get(SEARCH + "?by=id&order=asc&year=2020&wd=" + enc("合成")).ids()).containsExactly(301L, 303L, 304L);
        assertThat(get(SEARCH + "?wd=100%25").ids()).containsExactly(303L);
        assertThat(get(SEARCH + "?class=" + enc("剧情")).ids()).containsExactly(301L);

        // 分页：25 条 → 2 页；第 3 页 404
        Page p1 = get(SEARCH + "?wd=" + enc("分页样本"));
        Page p2 = get(SEARCH + "?page=2&wd=" + enc("分页样本"));
        assertThat(p1.ids()).hasSize(20);
        assertThat(p2.ids()).hasSize(5).doesNotContainAnyElementsOf(p1.ids());
        assertThat(p2.doc().selectFirst(".pagination__status").text()).isEqualTo("第 2 / 2 页");
        assertThat(get(SEARCH + "?page=3&wd=" + enc("分页样本")).status()).isEqualTo(404);

        // 400 / 404（交 PHP 的形态）：不回显
        Page bad = get(SEARCH + "?wd=%FF");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.doc().selectFirst("h1").text()).isEqualTo("搜索条件不合法");
        assertThat(get(SEARCH + "?by=rnd").status()).isEqualTo(404);
        Page none = get(SEARCH + "?wd=" + enc("不存在的关键字"));
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.doc().select(".search-empty")).hasSize(1);

        Map<String, String> after = snapshot();
        assertThat(after).as("请求前后全部表 CHECKSUM 与行数不变").isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("合成影片").doesNotContain(enc("合成影片")).doesNotContain(SECRET_PWD);
    }
}
