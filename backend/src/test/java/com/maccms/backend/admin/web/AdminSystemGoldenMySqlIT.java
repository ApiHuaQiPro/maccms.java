package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * B15 数据库工具的真实 MySQL 对照（./mvnw verify -Pmysql-it；24 文档第 9、10 节）。外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; →
 * 导入 install.sql → 取表结构指纹 → 写入与 PHP 黄金脚本相同的种子 → 启动打开了可写数据源与后台的应用（旧站文件按黄金的初始文件重建在临时目录）：
 * <ol>
 * <li>复放 PHP 8.0 与 7.3 黄金的 {@code database}、{@code database-plain} 场景：JSON 逐字节；备份文件内容逐字节（头部的主机/端口/库名、备份时间、
 * 登录写进 mac_admin 的随机串与时间各自不同，归一后比较）；批量替换后 mac_vod 的行与 PHP 相同；页面比较表格里的表名与备份列表。</li>
 * <li>备份导出只发出 SHOW 与 SELECT（包装只读数据源记录全部语句），导出前后 26 张表的数据不变。</li>
 * <li>还原、执行 SQL、优化、修复：带结构语句的请求一律被拒绝，期间没有任何语句发到数据库，表结构与数据不变。</li>
 * <li>批量替换只改含查找串的行，回滚演练：快照 → 替换 → {@code DmlRestore} 只用 DML 恢复 → diff 为空。</li>
 * </ol>
 * 结束时表结构指纹与导入 install.sql 时相同，删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminSystemGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final List<String> SQL = new CopyOnWriteArrayList<>();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static List<String> tables;
    private static ConfigurableApplicationContext app;
    private static MockMvc mvc;
    private static Path site;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_" + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法");
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
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            tables = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        site = Files.createTempDirectory("maccms-b15-mysql-site");
        AdminSystemGoldenTest.reset(site, null);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + site.resolve("application"),
                "--maccms.upload.dir=" + site.resolve("upload"), "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .initializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(new RecordingDataSource()))
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (site != null) {
            AdminSystemGoldenTest.deleteTree(site);
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

    /** 包装只读数据源：记录经它发出的全部 SQL 文本（prepareStatement/createStatement 后的 execute*）。 */
    static final class RecordingDataSource implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof DataSource ds)) {
                return bean;
            }
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                Object r = invoke(m, ds, a);
                if (r instanceof Connection c) {
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                        if ((m2.getName().equals("prepareStatement") || m2.getName().equals("prepareCall")
                                || m2.getName().equals("nativeSQL")) && a2 != null && a2[0] instanceof String s) {
                            SQL.add(s.strip().replaceAll("\\s+", " "));
                        }
                        Object r2 = invoke(m2, c, a2);
                        if (r2 instanceof Statement st && !(r2 instanceof java.sql.PreparedStatement)) {
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class}, (p3, m3, a3) -> {
                                if (m3.getName().startsWith("execute") && a3 != null && a3.length > 0 && a3[0] instanceof String s) {
                                    SQL.add(s.strip().replaceAll("\\s+", " "));
                                }
                                return invoke(m3, st, a3);
                            });
                        }
                        return r2;
                    });
                }
                return r;
            });
        }

        private static Object invoke(java.lang.reflect.Method m, Object target, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ------------------------------------------------------------------ 种子（与 admin_system_golden.php 的 seed() 相同）

    private static void seed() throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("SET SESSION sql_mode = ''");
            for (String t : List.of("mac_admin", "mac_group", "mac_type", "mac_vod")) {
                st.execute("DELETE FROM " + t + " WHERE 1 = 1");
            }
            st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                    + "(1, 'admin', '" + md5("admin888") + "', 1, ''), (2, 'editor', '" + md5("ed pass")
                    + "', 1, ',system/config,vodplayer/index,')");
            st.execute("INSERT INTO mac_group (group_id, group_name, group_status, group_type, group_popedom, group_points_day, "
                    + "group_points_week, group_points_month, group_points_year, group_points_free) VALUES "
                    + "(1, '游客', 1, ',1,', '', 0, 0, 0, 0, 0), (2, '默认会员', 1, ',1,', '', 0, 0, 0, 0, 0), "
                    + "(3, 'VIP<b>会员</b>', 1, ',1,', '', 10, 20, 30, 40, 0)");
            st.execute("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status, type_tpl, "
                    + "type_tpl_list, type_tpl_detail, type_tpl_play, type_tpl_down, type_key, type_des, type_title, type_union, "
                    + "type_extend, type_logo, type_pic, type_jumpurl) VALUES "
                    + "(1, '电影', 'dianying', 1, 1, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', '', "
                    + "'it''s \\\\ \"q\"\nline', '', '', '', '', '', ''), "
                    + "(2, '连续剧', 'lianxuju', 2, 1, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', '', '', '', "
                    + "'', '', '', '', '')");
            st.execute("INSERT INTO mac_vod (vod_id, type_id, vod_name, vod_en, vod_status, vod_play_from, vod_play_url, vod_content, "
                    + "vod_remarks, vod_pic) VALUES "
                    + "(1, 1, '测试视频一', 'ceshi1', 1, 'dplayer', '第1集$http://old.example.test/1.m3u8', '<p>http://old.example.test/a</p>', "
                    + "'HD', ''), (2, 2, '测试视频二', 'ceshi2', 1, 'dplayer', '第1集$http://old.example.test/2.m3u8', '', 'OLD old Old', '')");
        }
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void databaseScenariosMatchPhpGolden() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String version : List.of("80", "73")) {
            JsonNode g = AdminSystemGoldenTest.golden(version);
            seed();
            for (JsonNode s : g.get("scenarios")) {
                String label = s.get("label").asString();
                if (label.equals("database") || label.equals("database-plain")) {
                    compared += replay(version, s, problems);
                }
            }
        }
        System.out.println("[b15] database golden steps compared: " + compared);
        assertThat(compared).as("两版 PHP 各 18 + 3 步").isEqualTo(42);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-system-mysql-problems.txt"), String.join("\n\n", problems), StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异，详见 target/admin-system-mysql-problems.txt").isEmpty();
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        int n = 0;
        String label = s.get("label").asString();
        AdminSystemGoldenTest.reset(site, s.get("files"));
        if (s.has("config") && s.get("config").has("db")) {
            // 黄金脚本按场景的 config 改写 maccms.php（这里只有 db.compress = 0）
            Path f = site.resolve("application/extra/maccms.php");
            String c = Files.readString(f, StandardCharsets.UTF_8);
            assertThat(s.get("config").toString()).isEqualTo("{\"db\":{\"compress\":0}}");
            Files.writeString(f, c.replaceFirst("'compress' => 1,", "'compress' => 0,"), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        }
        Map<String, byte[]> last = AdminSystemGoldenTest.tracked(site);
        String sid = null;
        String token = "";
        for (JsonNode st : s.get("steps")) {
            String step = tag + ":" + label + "/" + st.get("label").asString();
            JsonNode req = st.get("request");
            String query = req.get("query").asString();
            if (query.contains("{backup_id}")) {
                query = query.replace("{backup_id}", backupId());
            }
            String post = req.get("post").asString();
            if (st.has("page")) {
                MockHttpServletResponse page = send(sid, "GET", req.get("path").asString(), query, null, false);
                sid = cookie(page, sid);
                token = token(page.getContentAsString(StandardCharsets.UTF_8));
            }
            MockHttpServletResponse r = send(sid, req.get("method").asString(), req.get("path").asString(), query.replace("{token}", token),
                    post.replace("{token}", token), req.get("ajax").asBoolean());
            sid = cookie(r, sid);
            String body = r.getContentAsString(StandardCharsets.UTF_8);
            String t = token(body);
            if (t != null) {
                token = t;
            }
            Map<String, byte[]> now = AdminSystemGoldenTest.tracked(site);
            Map<String, String> changed = AdminSystemGoldenTest.diff(last, now);
            last = now;
            String phpBody = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
            String want = view(st.get("status").asInt(), phpBody, changedOf(st));
            String got = view(r.getStatus(), body, changed);
            if (st.get("label").asString().equals("sql-page")) {
                // D-B15-1：执行 SQL 页面只给固定提示，不提供表单
                want = "status=200\npage notice\n";
                got = got.startsWith("status=200\npage ") && body.contains("库外手段完成") ? want : got;
            }
            if (!want.equals(got)) {
                problems.add(step + "\n" + AdminSystemGoldenTest.firstDiff(want, got));
            }
            // 页面的比较不能是两边都空：表列表里要有 mac_vod，导出后的备份列表里要有一行
            if (st.get("label").asString().equals("tables") && !got.contains("[mac_vod];")) {
                problems.add(step + " 表列表里没有 mac_vod\n" + got);
            }
            if (st.get("label").asString().equals("backups") && !got.contains("<stamp>")) {
                problems.add(step + " 备份列表是空的\n" + got);
            }
            if (st.has("rows") && st.get("rows").has("mac_vod")) {
                String a = st.get("rows").get("mac_vod").toString();
                String b = vodRows();
                if (!a.equals(b)) {
                    problems.add(step + " (mac_vod 行)\n" + AdminSystemGoldenTest.firstDiff(a, b));
                }
            }
            n++;
        }
        return n;
    }

    private static Map<String, String> changedOf(JsonNode st) {
        Map<String, String> m = new TreeMap<>();
        st.get("changed").properties().forEach(e -> m.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return m;
    }

    /** 观察结果：状态码、JSON 原样（页面取表格各行的文字，大小、时间一列归一）、变化的备份文件（归一后）。 */
    static String view(int status, String body, Map<String, String> changed) {
        StringBuilder sb = new StringBuilder("status=").append(status).append('\n');
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<")) {
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(body);
            sb.append("page ");
            for (org.jsoup.nodes.Element tr : d.select("tbody tr")) {
                List<String> cells = new ArrayList<>();
                for (org.jsoup.nodes.Element td : tr.select("td")) {
                    cells.add(td.text().replaceAll("[0-9.]+ (kb|K)$", "<n> $1").replaceAll("\\d{8}-\\d{6}", "<stamp>")
                            .replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", "<time>"));
                }
                // 表列表的"行数"列随表的历史（各场景的登录、插入）不同，只比较表名
                if (cells.size() == 7) {
                    cells = List.of(cells.get(1));
                }
                sb.append(cells).append(';');
            }
            sb.append('\n');
        } else {
            sb.append("body=").append(body).append('\n');
        }
        for (Map.Entry<String, String> e : changed.entrySet()) {
            sb.append("file ").append(e.getKey()).append(" = ").append(e.getValue() == null ? "<deleted>"
                    : normalizeBackup(new String(Base64.getDecoder().decode(e.getValue()), StandardCharsets.UTF_8))).append('\n');
        }
        return sb.toString();
    }

    /** 备份内容的归一：头部的连接信息；mac_admin 里登录写入的随机串、时间、IP、次数。 */
    static String normalizeBackup(String s) {
        return s.replaceAll("(?m)^-- Host     : .*$", "-- Host     : <host>").replaceAll("(?m)^-- Port     : .*$", "-- Port     : <port>")
                .replaceAll("(?m)^-- Database : .*$", "-- Database : <db>")
                .replaceAll("(?m)^(INSERT INTO `mac_admin` VALUES \\('\\d+', '[^']*', '[0-9a-f]{32}', )'[0-9a-f]*', ('\\d+', '[^']*'), .*$",
                        "$1'<random>', $2, <login>);");
    }

    private static String vodRows() throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT vod_id, vod_play_url, vod_remarks, vod_content FROM mac_vod ORDER BY vod_id")) {
            while (rs.next()) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 1; i <= 4; i++) {
                    byte[] b = rs.getBytes(i);
                    m.put(rs.getMetaData().getColumnLabel(i), b == null ? null : HexFormat.of().formatHex(b));
                }
                rows.add(m);
            }
        }
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rows);
    }

    private static String backupId() throws Exception {
        try (var s = Files.list(site.resolve("application/data/backup/database"))) {
            for (Path p : s.toList()) {
                Matcher m = Pattern.compile("^(\\d{8})-(\\d{6})-\\d+\\.sql").matcher(p.getFileName().toString());
                if (m.find()) {
                    return Long.toString(LocalDateTime.parse(m.group(1) + m.group(2), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond());
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ ② 导出只发出 SHOW/SELECT

    @Test
    @Order(2)
    void exportIssuesOnlyShowAndSelectAndChangesNoData() throws Exception {
        String sid = login();
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        AdminSystemGoldenTest.deleteTree(site.resolve("application/data/backup/database"));
        SQL.clear();
        StringBuilder all = new StringBuilder();
        for (String t : tables) {
            all.append(all.length() == 0 ? "" : "&").append("ids%5B%5D=").append(t);
        }
        MockHttpServletResponse r = send(sid, "POST", "/admin/database/export", "", all.toString(), true);
        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        List<String> sql = new ArrayList<>(SQL);
        assertThat(sql).isNotEmpty();
        assertThat(sql).allSatisfy(s -> assertThat(s).matches("(?i)^(SHOW|SELECT) .*"));
        assertThat(sql).anySatisfy(s -> assertThat(s).startsWith("SHOW CREATE TABLE"));
        assertThat(sql).noneSatisfy(s -> assertThat(s).matches("(?i)^(DROP|CREATE|ALTER|TRUNCATE|RENAME|OPTIMIZE|REPAIR|ANALYZE) .*"));
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables)).isEmpty()).as("导出不改数据").isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
        // 备份文件里有建表与删表文本（只是文件内容）
        try (var s = Files.list(site.resolve("application/data/backup/database"))) {
            Path f = s.filter(p -> p.getFileName().toString().contains(".sql")).findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(f);
            String text = f.toString().endsWith(".gz")
                    ? new String(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes)).readAllBytes(), StandardCharsets.UTF_8)
                    : new String(bytes, StandardCharsets.UTF_8);
            assertThat(text).contains("DROP TABLE IF EXISTS `mac_vod`;").contains("CREATE TABLE `mac_vod`");
        }
    }

    // ------------------------------------------------------------------ ③ 结构与维护语句的入口拒绝

    @Test
    @Order(3)
    void restoreSqlOptimizeAndRepairAreRefusedWithoutTouchingTheDatabase() throws Exception {
        String sid = login();
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        SQL.clear();
        String page = send(sid, "GET", "/admin/database/sql", "", null, false).getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("库外手段完成").doesNotContain("<textarea name=\"sql\"");
        for (String[] rq : List.of(
                new String[] {"POST", "/admin/database/sql", "sql=DROP+TABLE+mac_vod&__token__=x"},
                new String[] {"POST", "/admin/database/sql", "sql=CREATE+TABLE+mac_x+%28id+int%29"},
                new String[] {"GET", "/admin/database/optimize", "ids=mac_vod"},
                new String[] {"POST", "/admin/database/optimize", "ids%5B%5D=mac_vod&ids%5B%5D=mac_art"},
                new String[] {"GET", "/admin/database/repair", "ids=mac_vod"},
                new String[] {"GET", "/admin/database/import", "id=1793052318"})) {
            boolean get = rq[0].equals("GET");
            MockHttpServletResponse r = send(sid, rq[0], rq[1], get ? rq[2] : "", get ? null : rq[2], true);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).as(rq[1]).contains("\"code\":0").contains("库外手段完成");
        }
        // 只有每个请求的登录校验读管理员一行（B12 的会话检查），拒绝的入口本身不发出任何语句
        assertThat(SQL).as("拒绝的入口不发出任何语句").allSatisfy(q -> assertThat(q)
                .matches("^SELECT admin_id AS adminId, .* FROM mac_admin WHERE admin_id = \\?$"));
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables)).isEmpty()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ ④ 批量替换与回滚演练

    @Test
    @Order(4)
    void replaceChangesOnlyMatchingRowsAndRollsBackWithDml() throws Exception {
        seed();
        String sid = login();
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        String token = token(send(sid, "GET", "/admin/database/rep", "", null, false).getContentAsString(StandardCharsets.UTF_8));
        MockHttpServletResponse r = send(sid, "POST", "/admin/database/rep", "",
                "__token__=" + token + "&table=mac_vod&field=vod_remarks&findstr=OLD&tostr=NEW&where=1%3D1%3BDROP+TABLE+mac_vod", true);
        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot after = DatabaseSnapshot.take(c, tables);
            SnapshotDiff d = SnapshotDiff.between(before, after);
            assertThat(d.isEmpty()).isFalse();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT vod_id, vod_remarks FROM mac_vod ORDER BY vod_id")) {
                Map<Long, String> m = new TreeMap<>();
                while (rs.next()) {
                    m.put(rs.getLong(1), rs.getString(2));
                }
                // REPLACE 区分大小写：只换掉 OLD，old、Old 不变；没有 OLD 的行不变
                assertThat(m).containsEntry(1L, "HD").containsEntry(2L, "NEW old Old");
            }
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, after);
            assertThat(plan).allSatisfy(s -> assertThat(s.table()).isEqualTo("mac_vod"));
            DmlRestore.apply(c, plan);
            assertThat(SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables)).isEmpty()).as("只用 DML 恢复").isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 请求

    private static String login() throws Exception {
        MockHttpServletResponse r = send(null, "POST", "/admin/index/login", "", "admin_name=admin&admin_pwd=admin888", true);
        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        return cookie(r, null);
    }

    private static MockHttpServletResponse send(String sid, String method, String path, String query, String post, boolean ajax)
            throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(uri));
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded").content((post == null ? "" : post).getBytes(StandardCharsets.ISO_8859_1));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private static String cookie(MockHttpServletResponse r, String current) {
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                String v = h.substring("mac_admin_sid=".length(), h.indexOf(';'));
                return v.isEmpty() || h.contains("Max-Age=0") ? null : v;
            }
        }
        return current;
    }

    private static String token(String html) {
        Matcher m = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) {
        return com.maccms.backend.admin.application.LegacyInput.md5Hex(s.getBytes(StandardCharsets.UTF_8));
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
