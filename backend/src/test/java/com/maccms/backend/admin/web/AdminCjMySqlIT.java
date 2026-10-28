package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B20 自定义规则采集本地 HTML 源的端到端验证（28 文档第 9 节），真实 MySQL 一次性库 {@code maccms_it_<时间>_<随机>}，经后台入口发请求：
 * <ol>
 * <li>整条流程：测试网址 → 采集网址（两页列表，按跳转走完，重复与缺名称的计为重复，写节点采集时间）→ 采集内容（按跳转逐批走完，
 * 本地文件缺失的写 {@code null}）→ 发布方案（保存与回显）→ 内容发布页与查看 → 导入全部（经 B19 入库，成功的改为已发布）；</li>
 * <li>幂等：再采一遍网址不新增内容行，再导入一遍不新增视频；</li>
 * <li>并发：8 个请求同时采集同一节点的网址，内容行与采集历史不重复；8 个请求同时采集内容，每行只写一次；</li>
 * <li>删除内容连同全部网址的采集历史（旧版只删最后一个），删掉的网址可以重新采集；没有节点时"获取失败"；</li>
 * <li>回滚演练：快照 → 采集网址、内容、导入 → {@code DmlRestore} 只用 DML 恢复 → 逐行 diff 为空；结束时表结构指纹与导入时相同。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCjMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final String HOST = "http://cj.invalid";
    private static final Pattern DATA_URL = Pattern.compile("data-url=\"([^\"]*)\"");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static MockMvc mvc;
    private static Path work;
    private static Path htmlDir;
    private static String sid;

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
                        + "(1, '电影', 'dianying', 1, 1, 0, 1)");
            }
            insertNode(c, 1, "主流程", HOST + "/list_(*).html", 2);
            insertNode(c, 2, "并发", HOST + "/race_(*).html", 1);
            insertNode(c, 3, "演练", HOST + "/drill_(*).html", 1);
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        work = Files.createTempDirectory("maccms-b20-it");
        htmlDir = work.resolve("html");
        writePages();
        Path legacy = work.resolve("application");
        Path extra = Files.createDirectories(legacy.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        Path maccms = extra.resolve("maccms.php");
        String conf = Files.readString(maccms, StandardCharsets.UTF_8);
        assertThat(conf).contains("'admin_login_verify' => '1',");
        Files.writeString(maccms, conf.replace("'admin_login_verify' => '1',", "'admin_login_verify' => '0',"), StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + legacy,
                "--maccms.upload.dir=" + work.resolve("upload"), "--maccms.collect.local.cache-dir=" + work.resolve("cache"),
                "--maccms.collect.local.html-dir=" + htmlDir, "--server.port=0", "--server.address=127.0.0.1"));
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
        sid = login();
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

    // ------------------------------------------------------------------ 数据

    private static void insertNode(Connection c, int id, String name, String urlpage, int pages) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO mac_cj_node (nodeid, name, lastdate, sourcecharset, sourcetype, urlpage, "
                + "pagesize_start, pagesize_end, page_base, par_num, url_contain, url_except, url_start, url_end, title_rule, title_html_rule, "
                + "type_rule, type_html_rule, content_rule, content_html_rule, content_page_start, content_page_end, content_page_rule, "
                + "content_page, content_nextpage, down_attachment, watermark, coll_order, customize_config, program_config, mid) "
                + "VALUES (?, ?, 0, 'UTF-8', 1, ?, 1, ?, '', 1, '', '', '<ul class=\"list\">', '</ul>', '<h1>[内容]</h1>', '', '1', '', "
                + "'<div id=\"content\">[内容]</div>', '<script.*?</script>[|]', '', '', 1, 1, '', 0, 0, 2, ?, '', 1)")) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setString(3, urlpage);
            ps.setInt(4, pages);
            ps.setString(5, "{\"0\":{\"name\":\"作者\",\"en_name\":\"author\",\"rule\":\"<em>[内容]</em>\",\"html_rule\":\"\"}}");
            ps.executeUpdate();
        }
    }

    /** 列表页 list_1（文章 1～4、一个缺名称的链接）、list_2（文章 4 重复、5～7，文章 7 没有本地文件）；并发与演练节点各一页 10 篇。 */
    private static void writePages() throws IOException {
        page("list_1.html", list(1, 4).replace("</ul>", "<li><a href=\"/a/x.html\"><b></b></a></li></ul>"));
        page("list_2.html", list(4, 7));
        for (int i = 1; i <= 6; i++) {
            page("a/" + i + ".html", article("文章" + i, i));
        }
        page("race_1.html", list(101, 110));
        page("drill_1.html", list(201, 210));
        for (int i = 101; i <= 110; i++) {
            page("a/" + i + ".html", article("并发" + i, i));
        }
        for (int i = 201; i <= 210; i++) {
            page("a/" + i + ".html", article("演练" + i, i));
        }
    }

    private static String list(int from, int to) {
        StringBuilder sb = new StringBuilder("<html><body><a href=\"/nav.html\">导航</a><ul class=\"list\">\n");
        for (int i = from; i <= to; i++) {
            sb.append("<li><a href=\"/a/").append(i).append(".html\">标题").append(i).append("</a></li>\n");
        }
        return sb.append("</ul></body></html>").toString();
    }

    private static String article(String title, int i) {
        return "<html><body><h1> " + title + " </h1><em>作者" + i + "</em><div id=\"content\"><p>正文" + i + "</p><script>x()</script></div></body></html>";
    }

    private static void page(String rel, String html) throws IOException {
        Path p = htmlDir.resolve("cj.invalid").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, html, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ① 整条流程

    @Test
    @Order(1)
    void fullFlowCollectsUrlsContentsAndImports() throws Exception {
        // 测试网址（表单的"测试"）：展开序列网址并标出本地文件
        String test = get("/admin/cj/show_url", "data%5Bsourcetype%5D=1&data%5Bpagesize_start%5D=1&data%5Bpagesize_end%5D=3"
                + "&data%5Bpar_num%5D=1&urlpage1=" + enc(HOST + "/list_(*).html")).getContentAsString(StandardCharsets.UTF_8);
        assertThat(test).contains("<p>http://cj.invalid/list_1.html</p>", "<p>本地文件：cj.invalid/list_1.html</p>",
                "<p class=\"red\">本地文件：cj.invalid/list_3.html（不存在）</p>").doesNotContain("<script>");

        // 没有节点
        assertThat(get("/admin/cj/col_url", "id=99").getContentAsString(StandardCharsets.UTF_8)).contains("获取失败");

        // 采集网址：第 1 页 → 跳到第 2 页 → 完成
        MockHttpServletResponse r = get("/admin/cj/col_url", "id=1");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Content-Security-Policy")).contains("script-src 'self'");
        String p1 = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(p1).contains("让服务器休息一会，稍后继续<br>", "http://cj.invalid/list_1.html <br>共：5 数据，重复数据：1数据，去重入库4数据。",
                "<td>http://cj.invalid/a/1.html</td>", "<td>标题1</td>", "progress.js\" data-url=\"");
        assertThat(lastdate(1)).as("不是最后一页，不写采集时间").isZero();
        String p2 = follow(jumpUrl(p1));
        assertThat(p2).contains("URL采集完成<br>", "共：4 数据，重复数据：1数据，去重入库3数据。").doesNotContain("data-url=");
        assertThat(lastdate(1)).isPositive();
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE nodeid = 1 AND status = 1")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM mac_cj_history")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM mac_cj_history WHERE md5 = '" + md5(HOST + "/a/4.html") + "'")).isEqualTo(1);

        // 再采一遍：全部重复
        String again = get("/admin/cj/col_url", "id=1&page=2").getContentAsString(StandardCharsets.UTF_8);
        assertThat(again).contains("共：4 数据，重复数据：4数据，去重入库0数据。");
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content")).isEqualTo(7);

        // 采集内容：7 条一批完成（每批 20）
        String c1 = get("/admin/cj/col_content", "id=1").getContentAsString(StandardCharsets.UTF_8);
        assertThat(c1).startsWith("<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}span{font-weight:bold;color:#FF0000}</style><br>"
                + "正在采集内容，共【7】条，分1页，每页采集20条，当前1页<br>");
        assertThat(c1).contains("http://cj.invalid/a/1.html&nbsp;&nbsp;ok<br>",
                "http://cj.invalid/a/7.html&nbsp;&nbsp;<span>本地文件不存在（cj.invalid/a/7.html）</span><br>", "采集完成<br>");
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE status = 2")).isEqualTo(7);
        assertThat(one("SELECT data FROM mac_cj_content WHERE url = '" + HOST + "/a/7.html'")).isEqualTo("null");
        String data1 = one("SELECT data FROM mac_cj_content WHERE url = '" + HOST + "/a/1.html'");
        assertThat(data1).startsWith("{\"title\":\"\\u6587\\u7ae01\",\"type\":\"1\",\"time\":")
                .endsWith(",\"author\":\"\\u4f5c\\u80051\",\"content\":\"<p>\\u6b63\\u65871<\\/p>\"}");
        assertThat(get("/admin/cj/col_content", "id=1").getContentAsString(StandardCharsets.UTF_8))
                .contains("正在采集内容，共【0】条，分0页", "内容采集完成<br>");

        // 发布页与查看
        String pub = get("/admin/cj/publish", "id=1&status=2").getContentAsString(StandardCharsets.UTF_8);
        assertThat(pub).containsPattern("<li class=\"layui-this\"><a href=\"[^\"]*cj/publish[^\"]*id=1&amp;status=2\">").contains("已采集", "标题7")
                .contains("admin-list.js\" data-cur-url=\"", "data-count=\"7\"");
        long id1 = Long.parseLong(one("SELECT id FROM mac_cj_content WHERE url = '" + HOST + "/a/1.html'"));
        assertThat(get("/admin/cj/show", "id=" + id1).getContentAsString(StandardCharsets.UTF_8))
                .contains("value=\"http://cj.invalid/a/1.html\"", "title<input type=\"text\" class=\"layui-input\" value=\"文章1\">",
                        "author<input type=\"text\" class=\"layui-input\" value=\"作者1\">");

        // 发布方案：保存后回显
        String prog = get("/admin/cj/program", "id=1").getContentAsString(StandardCharsets.UTF_8);
        assertThat(prog).contains("<input type=\"hidden\" name=\"model_field[]\" value=\"vod_name\">vod_name", "value=\"author\"", "作者</option>");
        String post = "id=1&model_field%5B%5D=vod_name&node_field%5B%5D=title&funcs%5B%5D=trim"
                + "&model_field%5B%5D=type_id&node_field%5B%5D=type&funcs%5B%5D="
                + "&model_field%5B%5D=vod_content&node_field%5B%5D=content&funcs%5B%5D="
                + "&model_field%5B%5D=vod_actor&node_field%5B%5D=author&funcs%5B%5D="
                + "&model_field%5B%5D=vod_sub&node_field%5B%5D=&funcs%5B%5D=trim";
        assertThat(send("POST", "/admin/cj/program", "", post, sid, null).getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        assertThat(one("SELECT program_config FROM mac_cj_node WHERE nodeid = 1")).isEqualTo("{\"map\":{\"vod_name\":\"title\",\"type_id\":\"type\","
                + "\"vod_content\":\"content\",\"vod_actor\":\"author\"},\"funcs\":{\"vod_name\":\"trim\",\"type_id\":\"\",\"vod_content\":\"\",\"vod_actor\":\"\"}}");
        assertThat(get("/admin/cj/program", "id=1").getContentAsString(StandardCharsets.UTF_8))
                .contains("value=\"title\" selected", "value=\"trim\" selected");

        // 导入全部（每页 20）：6 条入库，没有页面的那条名称为空、跳过
        String into = get("/admin/cj/content_into", "id=1&all=1").getContentAsString(StandardCharsets.UTF_8);
        assertThat(into).contains("正在导入内容，共【7】条，分1页，每页采集20条，当前1页<br>", "新加入库，成功ok。<br>", "导入完成<br>");
        assertThat(count("SELECT COUNT(*) FROM mac_vod")).isEqualTo(6);
        assertThat(one("SELECT vod_name FROM mac_vod WHERE vod_actor = '作者3'")).isEqualTo("文章3");
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE status = 3")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE status = 2")).isEqualTo(1);

        // 再导入一遍：只剩没入库的那条，不新增视频
        assertThat(get("/admin/cj/content_into", "id=1&all=1").getContentAsString(StandardCharsets.UTF_8)).contains("共【1】条", "导入完成");
        assertThat(count("SELECT COUNT(*) FROM mac_vod")).isEqualTo(6);
    }

    // ------------------------------------------------------------------ ② 并发

    @Test
    @Order(2)
    void concurrentCollectionWritesEachUrlAndContentOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> get("/admin/cj/col_url", "id=2").getContentAsString(StandardCharsets.UTF_8));
            }
            int added = 0;
            for (Future<String> f : pool.invokeAll(tasks)) {
                String body = f.get();
                Matcher m = Pattern.compile("去重入库(\\d+)数据").matcher(body);
                assertThat(m.find()).as(body).isTrue();
                added += Integer.parseInt(m.group(1));
            }
            assertThat(added).as("8 个请求合计只记下 10 个网址").isEqualTo(10);
            assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE nodeid = 2")).isEqualTo(10);
            assertThat(count("SELECT COUNT(DISTINCT url) FROM mac_cj_content WHERE nodeid = 2")).isEqualTo(10);

            tasks.clear();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> get("/admin/cj/col_content", "id=2").getContentAsString(StandardCharsets.UTF_8));
            }
            int ok = 0;
            for (Future<String> f : pool.invokeAll(tasks)) {
                ok += f.get().split("&nbsp;&nbsp;ok<br>", -1).length - 1;
            }
            assertThat(ok).as("每条内容只由一个请求写入").isEqualTo(10);
            assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE nodeid = 2 AND status = 2 AND data LIKE '{\"title\"%'")).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ ③ 删除

    @Test
    @Order(3)
    void deletingContentsAlsoForgetsAllTheirUrls() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM mac_cj_content WHERE nodeid = 2 ORDER BY id LIMIT 3")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        String q = "ids=" + ids.get(0) + "," + ids.get(1) + "," + ids.get(2);
        assertThat(send("POST", "/admin/cj/content_del", "", q, sid, null).getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE nodeid = 2")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM mac_cj_history WHERE md5 IN ('" + md5(HOST + "/a/101.html") + "','" + md5(HOST + "/a/102.html")
                + "','" + md5(HOST + "/a/103.html") + "')")).as("三个网址的采集历史都删掉").isZero();
        assertThat(send("POST", "/admin/cj/content_del", "", "ids=abc", sid, null).getContentAsString(StandardCharsets.UTF_8)).contains("参数错误");
        // 删掉的网址可以重新采集
        assertThat(get("/admin/cj/col_url", "id=2").getContentAsString(StandardCharsets.UTF_8)).contains("重复数据：7数据，去重入库3数据");
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content WHERE nodeid = 2")).isEqualTo(10);
    }

    // ------------------------------------------------------------------ ④ 回滚演练

    @Test
    @Order(4)
    void rollbackDrillRestoresWithDmlOnly() throws Exception {
        try (Connection c = admin(dbName)) {
            List<String> tables = tables(c);
            DatabaseSnapshot before = DatabaseSnapshot.take(c, tables);
            assertThat(get("/admin/cj/col_url", "id=3").getContentAsString(StandardCharsets.UTF_8)).contains("去重入库10数据");
            assertThat(get("/admin/cj/col_content", "id=3").getContentAsString(StandardCharsets.UTF_8)).contains("采集完成<br>");
            String post = "id=3&model_field%5B%5D=vod_name&node_field%5B%5D=title&funcs%5B%5D="
                    + "&model_field%5B%5D=type_id&node_field%5B%5D=type&funcs%5B%5D="
                    + "&model_field%5B%5D=vod_content&node_field%5B%5D=content&funcs%5B%5D=";
            assertThat(send("POST", "/admin/cj/program", "", post, sid, null).getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
            assertThat(get("/admin/cj/content_into", "id=3&all=1").getContentAsString(StandardCharsets.UTF_8)).contains("导入完成");
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, tables);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            DmlRestore.apply(c, DmlRestore.plan(before, changed));
            SnapshotDiff diff = SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables));
            assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 请求

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String login() throws Exception {
        MockHttpServletResponse r = send("POST", "/admin/index/login", "", "admin_name=admin&admin_pwd=admin888", null, null);
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                return h.substring("mac_admin_sid=".length(), h.indexOf(';'));
            }
        }
        throw new IllegalStateException("登录失败");
    }

    private static MockHttpServletResponse get(String path, String query) throws Exception {
        return send("GET", path, query, null, sid, null);
    }

    private static MockHttpServletResponse send(String method, String path, String query, String post, String session, String referer)
            throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        b.header("User-Agent", "Mozilla/5.0 (b20)");
        b.with(req -> {
            req.setServerName("www.example.test");
            return req;
        });
        if (session != null) {
            b.header("Cookie", "mac_admin_sid=" + session);
        }
        if (referer != null) {
            b.header("Referer", referer);
        }
        if ("POST".equals(method)) {
            b.header("X-Requested-With", "XMLHttpRequest");
            b.contentType("application/x-www-form-urlencoded").content((post == null ? "" : post).getBytes(StandardCharsets.UTF_8));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private static String jumpUrl(String body) {
        Matcher m = DATA_URL.matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String follow(String url) throws Exception {
        int at = url.indexOf(E + "/");
        String rest = at >= 0 ? url.substring(at + E.length()) : url;
        int qm = rest.indexOf('?');
        String path = qm < 0 ? rest : rest.substring(0, qm);
        String query = qm < 0 ? "" : rest.substring(qm + 1);
        MockHttpServletResponse r = get(path, query);
        assertThat(r.getStatus()).isEqualTo(200);
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private static long lastdate(int nodeid) throws SQLException {
        return Long.parseLong(one("SELECT lastdate FROM mac_cj_node WHERE nodeid = " + nodeid));
    }

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(one(sql));
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
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
