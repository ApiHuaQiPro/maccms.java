package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.collect.application.CollectBindings;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B18 资源库预览的端到端验证（26 文档第 8 节），真实 MySQL 一次性库 {@code maccms_it_<时间>_<随机>}：经后台入口完成"打开预览 → 上传载入 →
 * 预览（绑定、入库预检、本地筛选、分页）→ 从本地目录载入 → 拒绝 XXE 与超大文件 → 没有断点时的断点续采只跳转 → 清除预览缓存"
 * （入库按钮见 {@code AdminCollectIngestMySqlIT}），并证明：
 * <ul>
 * <li>整个过程前后全库逐行相同（{@link DatabaseSnapshot}，登录之后取第一次快照）；表结构指纹与导入时相同；</li>
 * <li>XXE 文件里的外部 DTD 指向本机一个监听端口，整个过程中端口上没有连接；</li>
 * <li>写出的文件只在配置的缓存目录里；清除后缓存目录里没有缓存项；旧站的 {@code bind.php} 与本地文件目录不变。</li>
 * </ul>
 */
class AdminCollectPreviewMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final String URL = "http://provide.invalid/api.php/provide/vod/";
    private static final String FLAG = CollectBindings.flag(URL);

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static MockMvc mvc;
    private static Path work;
    private static Path cacheDir;
    private static Path sourceDir;
    private static Path bindFile;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_" + HexFormat.of().formatHex(rnd);
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
                st.execute("SET SESSION sql_mode = ''");
                st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                        + "(1, 'admin', '" + md5("admin888") + "', 1, '')");
                st.execute("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
                        + "(1, '电影', 'dianying', 1, 1, 0, 1), (2, '连续剧', 'lianxuju', 2, 1, 0, 1)");
                st.execute("INSERT INTO mac_collect (collect_name, collect_url, collect_type, collect_mid, collect_param) VALUES "
                        + "('本地源', '" + URL + "', 2, 1, '')");
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        work = Files.createTempDirectory("maccms-b18-it");
        Path legacy = work.resolve("application");
        Path extra = Files.createDirectories(legacy.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        // 后台登录不要验证码（测试直接提交账号密码）
        Path maccms = extra.resolve("maccms.php");
        String conf = Files.readString(maccms, StandardCharsets.UTF_8);
        assertThat(conf).contains("'admin_login_verify' => '1',");
        Files.writeString(maccms, conf.replace("'admin_login_verify' => '1',", "'admin_login_verify' => '0',"), StandardCharsets.UTF_8);
        bindFile = extra.resolve("bind.php");
        Files.writeString(bindFile, "<?php\nreturn array (\n  '" + FLAG + "_1' => 1,\n);", StandardCharsets.UTF_8);
        cacheDir = work.resolve("cache");
        sourceDir = Files.createDirectories(work.resolve("source"));
        Files.writeString(sourceDir.resolve("dir.json"), "{\"page\":1,\"list\":[{\"vod_id\":7,\"type_id\":1,\"vod_name\":\"目录里的片\"}]}",
                StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + legacy,
                "--maccms.upload.dir=" + work.resolve("upload"), "--maccms.collect.local.source-dir=" + sourceDir,
                "--maccms.collect.local.cache-dir=" + cacheDir, "--maccms.collect.local.max-file-bytes=50000",
                "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET).run(args.toArray(String[]::new));
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
        if (work != null) {
            deleteTree(work);
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

    private static String q(String ac, String mid, String type, String extra) {
        return "ac=" + ac + "&cjflag=" + FLAG + "&cjurl=" + URLEncoder.encode(URL, StandardCharsets.UTF_8) + "&h=&t=&ids=&wd=&type=" + type
                + "&mid=" + mid + "&opt=0&sync_pic_opt=0&filter=0&filter_from=&param=" + extra;
    }

    private static byte[] vodJson() {
        StringBuilder sb = new StringBuilder("{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":25,\"list\":[");
        for (int i = 1; i <= 25; i++) {
            sb.append(i > 1 ? "," : "").append("{\"vod_id\":").append(i).append(",\"type_id\":").append(i == 3 ? 9 : 1)
                    .append(",\"type_name\":\"远程\",\"vod_name\":\"").append(i == 2 ? "色戒 片" : i == 4 ? "<b>标签</b>" : "片" + i)
                    .append("\",\"vod_play_from\":\"ckm3u8\",\"vod_time\":\"2026-10-01 10:00:00\"}");
        }
        sb.append("],\"class\":[{\"type_id\":1,\"type_name\":\"动作\"},{\"type_id\":9,\"type_name\":\"未绑\"}]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void previewFlowWritesNoDatabaseRowsAndMakesNoConnections() throws Exception {
        deleteTree(cacheDir);
        String sid = login();
        byte[] bindBefore = Files.readAllBytes(bindFile);
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables(c));
        }
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);

            // ① 还没有载入：只显示"本地文件"一栏，列出本地目录的文件
            MockHttpServletResponse r = send(sid, "GET", "/admin/collect/api", q("list", "1", "2", ""), false);
            assertThat(r.getStatus()).as(r.getHeader("Location")).isEqualTo(200);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("尚未载入", "dir.json").doesNotContain("pageListForm");

            // ② 上传载入
            r = upload(sid, q("localload", "1", "2", ""), "vod.json", vodJson());
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1").contains("共 25 条");
            String page = send(sid, "GET", "/admin/collect/api", q("list", "1", "2", ""), false).getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).contains("vod.json", "pageListForm", "data-count=\"25\"", "data-limit=\"20\"", "data-curr=\"1\"",
                    "id=\"" + FLAG + "_1\"", "<span class=\"red\">[电影]</span>", "[绑定]", "片1", "色戒 片",
                    "&lt;b&gt;标签&lt;/b&gt;", "collect-api.js");
            // 入库预检：命中过滤词、分类未绑定
            assertThat(page).contains("色戒 片 <span class=\"red\">[").contains("片3 <span class=\"red\">[");
            assertThat(page).doesNotContain("片21");
            // 第 2 页、按远程分类、按名称
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", "") + "&page=2&limit=20", false)
                    .getContentAsString(StandardCharsets.UTF_8)).contains("片21", "片25").doesNotContain(">片1<");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", "").replace("&t=", "&t=9"), false)
                    .getContentAsString(StandardCharsets.UTF_8)).contains("data-count=\"1\"", "片3");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", "").replace("&wd=", "&wd=" + URLEncoder.encode("色戒", StandardCharsets.UTF_8)), false)
                    .getContentAsString(StandardCharsets.UTF_8)).contains("data-count=\"1\"");

            // ③ 从本地目录载入
            r = send(sid, "POST", "/admin/collect/api", q("localload", "1", "2", ""), "local=dir.json", true);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", ""), false).getContentAsString(StandardCharsets.UTF_8))
                    .contains("目录里的片", "dir.json");
            r = send(sid, "POST", "/admin/collect/api", q("localload", "1", "2", ""), "local=" + URLEncoder.encode("../application/extra/bind.php",
                    StandardCharsets.UTF_8), true);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":0", "文件不存在");

            // ④ XXE（外部 DTD 指向本机监听端口）与超大文件被拒绝，缓存不变
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE rss SYSTEM \"http://127.0.0.1:" + server.getLocalPort() + "/x.dtd\">"
                    + "<rss><list><video><name>x</name></video></list></rss>";
            r = upload(sid, q("localload", "1", "1", ""), "x.xml", xxe.getBytes(StandardCharsets.UTF_8));
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":0", "DOCTYPE");
            r = upload(sid, q("localload", "1", "2", ""), "big.json", new byte[50_001]);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":0");
            r = upload(sid, q("localload", "1", "2", ""), "bad.json", "{\"list\":[".getBytes(StandardCharsets.UTF_8));
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":0", "JSON");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", ""), false).getContentAsString(StandardCharsets.UTF_8))
                    .contains("目录里的片");

            // ⑤ 其他种类：文章（分类绑定弹窗的 tab）
            r = upload(sid, q("localload", "2", "", ""), "art.json",
                    "{\"list\":[{\"art_id\":1,\"type_id\":1,\"art_name\":\"文章甲\",\"art_from\":\"来源\"}],\"class\":[{\"type_id\":1,\"type_name\":\"新闻\"}]}"
                            .getBytes(StandardCharsets.UTF_8));
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "2", "", ""), false).getContentAsString(StandardCharsets.UTF_8))
                    .contains("文章甲", "来源", "tab=art&amp;col=" + FLAG + "_1");

            // ⑥ 入库按钮与断点续采是 B19 增量 3（AdminCollectIngestMySqlIT）；没有断点时 collect/load 只跳转、不写库；不认识的 mid、GET 的载入"参数错误"
            assertThat(send(sid, "GET", "/admin/collect/load", "flag=vod", false).getContentAsString(StandardCharsets.UTF_8))
                    .contains("progress.js").doesNotContain("断点位置");
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "3", "2", ""), true).getContentAsString(StandardCharsets.UTF_8))
                    .contains("参数错误");
            assertThat(send(sid, "GET", "/admin/collect/api", q("localload", "1", "2", ""), true).getContentAsString(StandardCharsets.UTF_8))
                    .contains("参数错误");

            // ⑦ 缓存只在缓存目录；清除后没有缓存项
            assertThat(cacheEntries()).hasSize(2);
            r = send(sid, "POST", "/admin/collect/api", q("localclear", "1", "2", ""), "", true);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
            r = send(sid, "POST", "/admin/collect/api", q("localclear", "2", "", ""), "", true);
            assertThat(cacheEntries()).isEmpty();
            assertThat(send(sid, "GET", "/admin/collect/api", q("list", "1", "2", ""), false).getContentAsString(StandardCharsets.UTF_8))
                    .contains("尚未载入");

            assertThatThrownBy(server::accept).as("整个过程中没有出站连接").isInstanceOf(SocketTimeoutException.class);
        }
        DatabaseSnapshot after;
        try (Connection c = admin(dbName)) {
            after = DatabaseSnapshot.take(c, tables(c));
        }
        SnapshotDiff diff = SnapshotDiff.between(before, after);
        assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
        assertThat(Files.readAllBytes(bindFile)).isEqualTo(bindBefore);
        try (Stream<Path> s = Files.list(sourceDir)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("dir.json");
        }
    }

    /**
     * 经真实的 Tomcat（MockMvc 不经过 servlet 的 multipart 解析与它的大小上限）：上传能载入；超过 {@code spring.servlet.multipart.max-file-size}
     * （默认 2MB）的上传返回"超过上传上限"而不是 500；库不变。
     */
    @Test
    void realHttpMultipartUploadAndItsSizeLimit() throws Exception {
        deleteTree(cacheDir);
        String sid = login();
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables(c));
        }
        int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) app).getWebServer().getPort();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String ok = multipart(http, port, sid, q("localload", "11", "", ""), "site.json",
                "{\"list\":[{\"website_id\":1,\"type_id\":1,\"website_name\":\"网址甲\",\"website_area\":\"大陆\"}],\"class\":[{\"type_id\":1,\"type_name\":\"导航\"}]}".getBytes(StandardCharsets.UTF_8));
        assertThat(ok).contains("\"code\":1");
        assertThat(send(sid, "GET", "/admin/collect/api", q("list", "11", "", ""), false).getContentAsString(StandardCharsets.UTF_8))
                .contains("网址甲", "大陆", "tab=website");
        String big = multipart(http, port, sid, q("localload", "11", "", ""), "big.json", new byte[3 * 1024 * 1024]);
        assertThat(big).contains("\"code\":0", "上传上限");
        send(sid, "POST", "/admin/collect/api", q("localclear", "11", "", ""), "", true);
        assertThat(cacheEntries()).isEmpty();
        DatabaseSnapshot after;
        try (Connection c = admin(dbName)) {
            after = DatabaseSnapshot.take(c, tables(c));
        }
        SnapshotDiff diff = SnapshotDiff.between(before, after);
        assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
    }

    private static String multipart(java.net.http.HttpClient http, int port, String sid, String query, String name, byte[] content)
            throws Exception {
        String boundary = "----b18" + System.nanoTime();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + E + "/admin/collect/api?" + query))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (b18)")
                .header("Cookie", "mac_admin_sid=" + sid)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        java.net.http.HttpResponse<String> r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body();
    }

    // ------------------------------------------------------------------ 请求

    private static String login() throws Exception {
        MockHttpServletResponse r = send(null, "POST", "/admin/index/login", "", "admin_name=admin&admin_pwd=admin888", true);
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                return h.substring("mac_admin_sid=".length(), h.indexOf(';'));
            }
        }
        throw new IllegalStateException("登录失败");
    }

    private static MockHttpServletResponse send(String sid, String method, String path, String query, boolean ajax) throws Exception {
        return send(sid, method, path, query, null, ajax);
    }

    private static MockHttpServletResponse send(String sid, String method, String path, String query, String post, boolean ajax)
            throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        b.header("User-Agent", "Mozilla/5.0 (b18)");
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded").content((post == null ? "" : post).getBytes(StandardCharsets.UTF_8));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private static MockHttpServletResponse upload(String sid, String query, String name, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "application/octet-stream", content);
        var b = MockMvcRequestBuilders.multipart(java.net.URI.create(E + "/admin/collect/api?" + query)).file(file);
        b.header("X-Requested-With", "XMLHttpRequest");
        b.header("User-Agent", "Mozilla/5.0 (b18)");
        b.header("Cookie", "mac_admin_sid=" + sid);
        return mvc.perform(b).andReturn().getResponse();
    }

    private static List<Path> cacheEntries() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        }
    }

    private static List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
