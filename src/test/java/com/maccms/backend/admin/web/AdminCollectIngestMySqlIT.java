package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.collect.application.CollectBindings;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B19 增量 3 后台入库与断点续采的端到端验证（27 文档第 12 节），真实 MySQL 一次性库 {@code maccms_it_<时间>_<随机>}，经后台入口发请求（HTTP 契约）：
 * <ol>
 * <li>"采集所有"按跳转逐页走完：每页的输出（标题、样式、页码与文件名、逐行结果、跳转）、过程中采集源列表与断点采集页出现断点按钮、
 * {@code collect/load} 跳到断点、完成后断点删除；入库结果与入库规则一致（未绑定分类、过滤词跳过）；</li>
 * <li>幂等：同一批再走一遍，全库逐行不变；</li>
 * <li>断点续采：只做第 1 页就中断，经 {@code collect/load} 从断点续上，断点那一页再做一遍也不新增重复行；</li>
 * <li>"采集选中"只入库选中的行、回到来源页（不是本站的来源页时回到预览）；"采集当天"按更新时间筛选（文章）；</li>
 * <li>没有载入时提示、不写库、不记断点；8 个并发请求入库同一页，不写出重复行；</li>
 * <li>回滚演练：快照 → 视频与文章两批入库 → {@code DmlRestore} 只用 DML 恢复 → 逐行 diff 为空；结束时表结构指纹与导入时相同。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCollectIngestMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final String VOD_URL = "http://provide.invalid/api.php/provide/vod/";
    private static final String ART_URL = "http://provide.invalid/api.php/provide/art/";
    private static final String RACE_URL = "http://race.invalid/api.php/provide/vod/";
    private static final String DRILL_URL = "http://drill.invalid/api.php/provide/vod/";
    private static final Pattern DATA_URL = Pattern.compile("data-url=\"([^\"]*)\"");
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");

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
                        + "(1, '电影', 'dianying', 1, 1, 0, 1), (2, '连续剧', 'lianxuju', 2, 1, 0, 1), (3, '新闻', 'xinwen', 3, 2, 0, 1)");
                st.execute("INSERT INTO mac_collect (collect_name, collect_url, collect_type, collect_mid, collect_param) VALUES "
                        + "('本地源', '" + VOD_URL + "', 2, 1, '')");
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
        }
        work = Files.createTempDirectory("maccms-b19i3-it");
        Path legacy = work.resolve("application");
        Path extra = Files.createDirectories(legacy.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        Path maccms = extra.resolve("maccms.php");
        String conf = Files.readString(maccms, StandardCharsets.UTF_8);
        assertThat(conf).contains("'admin_login_verify' => '1',", "'collect_timespan' => '3',");
        Files.writeString(maccms, conf.replace("'admin_login_verify' => '1',", "'admin_login_verify' => '0',"), StandardCharsets.UTF_8);
        Files.writeString(extra.resolve("bind.php"), "<?php\nreturn array (\n  '" + flag(VOD_URL) + "_1' => 1,\n  '" + flag(ART_URL)
                + "_1' => 3,\n  '" + flag(RACE_URL) + "_1' => 2,\n  '" + flag(DRILL_URL) + "_1' => 2,\n);", StandardCharsets.UTF_8);
        cacheDir = work.resolve("cache");
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + legacy,
                "--maccms.upload.dir=" + work.resolve("upload"), "--maccms.collect.local.cache-dir=" + cacheDir,
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

    private static String flag(String url) {
        return CollectBindings.flag(url);
    }

    /** 采集按钮的参数（旧 {@code collectUrl}：{@code page}、{@code limit} 是没替换的占位符）。 */
    private static String q(String url, String ac, String mid, String extra) {
        return "cjflag=" + flag(url) + "&cjurl=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&h=&t=&ids=&wd=&type=2&mid=" + mid
                + "&opt=0&sync_pic_opt=0&filter=0&filter_from=&param=&page=%7Bpage%7D&limit=%7Blimit%7D" + extra + "&ac=" + ac;
    }

    /** {@code n} 部视频：第 7 部远程分类 9（未绑定），第 11 部名称含过滤词，其余绑定到本站分类 {@code local}。 */
    private static byte[] vods(String prefix, int n) {
        StringBuilder sb = new StringBuilder("{\"page\":1,\"pagecount\":1,\"list\":[");
        for (int i = 1; i <= n; i++) {
            String name = i == 11 ? prefix + "色戒" + i : prefix + i;
            sb.append(i > 1 ? "," : "").append("{\"vod_id\":").append(i).append(",\"type_id\":").append(i == 7 ? 9 : 1)
                    .append(",\"vod_name\":\"").append(name).append("\",\"vod_actor\":\"演员").append(i)
                    .append("\",\"vod_content\":\"正文").append(i).append("\",\"vod_play_from\":\"dplayer\",\"vod_play_url\":\"第1集$http://v.invalid/")
                    .append(prefix).append(i).append(".m3u8\",\"vod_time\":\"2026-10-01 10:00:00\"}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ① 逐页走完、断点按钮、完成后删除断点

    @Test
    @Order(1)
    void cjallWalksAllPagesWithBreakpointThenFinishes() throws Exception {
        upload(q(VOD_URL, "localload", "1", ""), "vod.json", vods("片", 45));
        String index = get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8);
        assertThat(index).doesNotContain("断点采集");

        MockHttpServletResponse r = get("/admin/collect/api", q(VOD_URL, "cjall", "1", ""));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.getHeader("Content-Security-Policy")).contains("script-src 'self'");
        String p1 = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(p1).startsWith("<title>0/3 collecting..</title><br><style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
                + "span{font-weight:bold;color:#FF0000}</style><br>[vod_data] 当前采集任务<strong class=\"green\">1</strong>/"
                + "<span class=\"green\">3</span>页 采集地址&nbsp;vod.json<br>1、片1 <font color='green'>新加入库，成功ok。</font><br>");
        assertThat(p1).contains("7、片7 <font color='red'>分类未绑定，跳过err</font><br>",
                "11、片色戒11 <font color='red'>数据在过滤单中，跳过err</font><br>", "20、片20 <font color='green'>",
                "/admin-assets/progress.js\" data-url=\"", "data-seconds=\"3\"", "<span>暂停3秒后继续  >>>  </span>",
                ">如果您的浏览器没有自动跳转，请点击这里</a><br>");
        assertThat(p1).doesNotContain("<script>").doesNotContain("21、");
        String next = jumpUrl(p1);
        assertThat(next).contains("page=2", "ac=cjall");

        // 过程中：采集源列表与断点采集页有视频的断点按钮；collect/load 跳到断点（第 1 页的地址）
        index = get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8);
        assertThat(index).contains("?flag=vod\" class=\"layui-btn layui-btn-danger \">【进入视频断点采集】</a>").doesNotContain("文章断点");
        assertThat(get("/admin/collect/union", "").getContentAsString(StandardCharsets.UTF_8)).contains("【进入视频断点采集】");
        String load = get("/admin/collect/load", "flag=vod").getContentAsString(StandardCharsets.UTF_8);
        assertThat(load).startsWith("正在载入断点位置，请稍后。。。<script src=").contains("data-seconds=\"0\"");
        assertThat(jumpUrl(load)).contains("ac=cjall").contains("page=%7Bpage%7D");

        String p2 = follow(next);
        assertThat(p2).startsWith("<title>2/3 collecting..</title>").contains("1、片21 <font color='green'>");
        assertThat(jumpUrl(get("/admin/collect/load", "flag=vod").getContentAsString(StandardCharsets.UTF_8))).contains("page=2");
        String p3 = follow(jumpUrl(p2));
        assertThat(p3).contains("<span class=\"green\">3</span>页", "5、片45 <font color='green'>", "数据采集完成。<br>");
        String back = jumpUrl(p3);
        assertThat(back).contains("ac=list").doesNotContain("page=").doesNotContain("ids=");
        assertThat(get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8)).doesNotContain("断点采集");
        // 没有断点时 collect/load 跳回来源页（旧 mac_get_refer：urldecode 后 trim）；没有来源页时跳到采集源列表（旧版跳到空地址）；
        // 别的站点的来源页在入口处就被拒绝（D-B12-3）
        assertThat(jumpUrl(getWithReferer("/admin/collect/load", "flag=vod", "http://www.example.test" + E + "/admin/collect/union.html?x=%41")
                .getContentAsString(StandardCharsets.UTF_8))).isEqualTo("http://www.example.test" + E + "/admin/collect/union.html?x=A");
        assertThat(jumpUrl(get("/admin/collect/load", "flag=vod").getContentAsString(StandardCharsets.UTF_8))).endsWith("/admin/collect/index.html");
        MockHttpServletResponse evil = getWithReferer("/admin/collect/load", "flag=vod", "http://evil.invalid/x");
        assertThat(evil.getStatus()).isEqualTo(403);

        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '片%'")).isEqualTo(43);
        assertThat(count("SELECT COUNT(DISTINCT vod_name) FROM mac_vod WHERE vod_name LIKE '片%'")).isEqualTo(43);
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name = '片45' AND type_id = 1 AND vod_play_from = 'dplayer'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ② 幂等

    @Test
    @Order(2)
    void rerunningTheSameBatchChangesNothing() throws Exception {
        DatabaseSnapshot before = snapshot();
        String page = get("/admin/collect/api", q(VOD_URL, "cjall", "1", "")).getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("1、片1 <font color='red'>无需更新。</font>", "7、片7 <font color='red'>分类未绑定，跳过err</font>")
                .doesNotContain("新加入库");
        int pages = 1;
        while (!page.contains("数据采集完成。")) {
            page = follow(jumpUrl(page));
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        SnapshotDiff diff = SnapshotDiff.between(before, snapshot());
        assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
    }

    // ------------------------------------------------------------------ ③ 断点续采

    @Test
    @Order(3)
    void interruptedRunResumesFromTheBreakpointWithoutDuplicates() throws Exception {
        upload(q(VOD_URL, "localload", "1", ""), "vod2.json", vods("续", 50));
        String p1 = get("/admin/collect/api", q(VOD_URL, "cjall", "1", "")).getContentAsString(StandardCharsets.UTF_8);
        String p2 = follow(jumpUrl(p1));
        assertThat(p2).contains("1、续21 <font color='green'>新加入库");
        // 第 2 页做完后"中断"（不再跟随跳转）；再从断点续采：断点是第 2 页，这一页重做一遍不新增
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '续%'")).isEqualTo(38);
        String load = get("/admin/collect/load", "flag=vod").getContentAsString(StandardCharsets.UTF_8);
        String again = follow(jumpUrl(load));
        assertThat(again).contains("<title>2/3 collecting..</title>").doesNotContain("新加入库");
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '续%'")).isEqualTo(38);
        String p3 = follow(jumpUrl(again));
        assertThat(p3).contains("数据采集完成。");
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '续%'")).isEqualTo(48);
        assertThat(count("SELECT COUNT(DISTINCT vod_name) FROM mac_vod WHERE vod_name LIKE '续%'")).isEqualTo(48);
        assertThat(get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8)).doesNotContain("断点采集");
    }

    // ------------------------------------------------------------------ ④ 采集选中、采集当天

    @Test
    @Order(4)
    void selectedRowsAndTodaysArticles() throws Exception {
        upload(q(VOD_URL, "localload", "1", ""), "sel.json", vods("选", 30));
        String referer = "http://www.example.test" + E + "/admin/collect/api?ac=list&mid=1";
        MockHttpServletResponse r = getWithReferer("/admin/collect/api", q(VOD_URL, "cjsel", "1", "").replace("&ids=", "&ids=2%2C25%2C29"),
                referer);
        String body = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("1、选2 <font color='green'>", "2、选25 <font color='green'>", "3、选29 <font color='green'>", "数据采集完成。")
                .doesNotContain("4、");
        assertThat(jumpUrl(body)).isEqualTo(referer);
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '选%'")).isEqualTo(3);
        assertThat(get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8)).doesNotContain("断点采集");
        // 没有来源页：回到预览；来源页不是本站：入口处拒绝（D-B12-3），不入库
        body = get("/admin/collect/api", q(VOD_URL, "cjsel", "1", "").replace("&ids=", "&ids=3")).getContentAsString(StandardCharsets.UTF_8);
        assertThat(jumpUrl(body)).contains("/admin/collect/api.html?").contains("ac=list").doesNotContain("ids=");
        r = getWithReferer("/admin/collect/api", q(VOD_URL, "cjsel", "1", "").replace("&ids=", "&ids=4"), "javascript:alert(1)");
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '选%'")).isEqualTo(4);

        // 文章"采集当天"（h=24）：只入库一天内更新的
        String now = LocalDateTime.now(PRC).minusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String json = "{\"list\":[{\"art_id\":1,\"type_id\":1,\"art_name\":\"今文\",\"art_content\":\"正文\",\"art_time\":\"" + now + "\"},"
                + "{\"art_id\":2,\"type_id\":1,\"art_name\":\"旧文\",\"art_content\":\"正文\",\"art_time\":\"2020-01-01 00:00:00\"}]}";
        upload(q(ART_URL, "localload", "2", ""), "art.json", json.getBytes(StandardCharsets.UTF_8));
        body = get("/admin/collect/api", q(ART_URL, "cjday", "2", "").replace("&h=", "&h=24")).getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("<style").contains("[art_data] ", "1今文<font color=green>", "数据采集完成。").doesNotContain("旧文");
        assertThat(count("SELECT COUNT(*) FROM mac_art WHERE art_name IN ('今文', '旧文') AND type_id = 3")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ⑤ 没有载入、并发

    @Test
    @Order(5)
    void notLoadedWritesNothingAndConcurrentRunsDoNotDuplicate() throws Exception {
        DatabaseSnapshot before = snapshot();
        MockHttpServletResponse r = get("/admin/collect/api", q(RACE_URL, "cjall", "1", ""));
        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("没有载入的本地文件");
        assertThat(SnapshotDiff.between(before, snapshot()).isEmpty()).isTrue();
        assertThat(get("/admin/collect/index", "").getContentAsString(StandardCharsets.UTF_8)).doesNotContain("断点采集");

        upload(q(RACE_URL, "localload", "1", ""), "race.json", vods("并", 20));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> get("/admin/collect/api", q(RACE_URL, "cjall", "1", "")).getContentAsString(StandardCharsets.UTF_8));
            }
            int added = 0;
            for (Future<String> f : pool.invokeAll(tasks)) {
                String body = f.get();
                assertThat(body).contains("数据采集完成。");
                added += body.split("新加入库", -1).length - 1;
            }
            assertThat(added).as("8 个请求合计只新增 18 次").isEqualTo(18);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name LIKE '并%'")).isEqualTo(18);
        assertThat(count("SELECT COUNT(DISTINCT vod_name) FROM mac_vod WHERE vod_name LIKE '并%'")).isEqualTo(18);
    }

    // ------------------------------------------------------------------ ⑥ 回滚演练

    @Test
    @Order(6)
    void rollbackDrillRestoresWithDmlOnly() throws Exception {
        try (Connection c = admin(dbName)) {
            List<String> tables = tables(c);
            DatabaseSnapshot before = DatabaseSnapshot.take(c, tables);
            upload(q(DRILL_URL, "localload", "1", ""), "drill.json", vods("演", 25));
            String page = get("/admin/collect/api", q(DRILL_URL, "cjall", "1", "")).getContentAsString(StandardCharsets.UTF_8);
            page = follow(jumpUrl(page));
            assertThat(page).contains("数据采集完成。");
            // 再改一行已有的：同名视频换播放地址 → 更新
            upload(q(VOD_URL, "localload", "1", ""), "upd.json", ("{\"list\":[{\"vod_id\":1,\"type_id\":1,\"vod_name\":\"片1\",\"vod_actor\":\"演员1\","
                    + "\"vod_content\":\"正文1\",\"vod_play_from\":\"dplayer\",\"vod_play_url\":\"第1集$http://v.invalid/new.m3u8\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(get("/admin/collect/api", q(VOD_URL, "cjall", "1", "")).getContentAsString(StandardCharsets.UTF_8))
                    .contains("1、片1 <font color='green'>");
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, tables);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, changed);
            DmlRestore.apply(c, plan);
            SnapshotDiff diff = SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables));
            assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 请求

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

    private static MockHttpServletResponse getWithReferer(String path, String query, String referer) throws Exception {
        return send("GET", path, query, null, sid, referer);
    }

    private static MockHttpServletResponse send(String method, String path, String query, String post, String session, String referer)
            throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        b.header("User-Agent", "Mozilla/5.0 (b19)");
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

    private static void upload(String query, String name, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "application/octet-stream", content);
        var b = MockMvcRequestBuilders.multipart(java.net.URI.create(E + "/admin/collect/api?" + query)).file(file);
        b.header("X-Requested-With", "XMLHttpRequest");
        b.header("User-Agent", "Mozilla/5.0 (b19)");
        b.header("Cookie", "mac_admin_sid=" + sid);
        String body = mvc.perform(b).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":1");
    }

    /** 输出里跳转的地址（progress.js 的 {@code data-url}，反转义）。 */
    private static String jumpUrl(String body) {
        Matcher m = DATA_URL.matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** 跟随跳转（地址里入口之后的路径与查询串）。 */
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

    private static int count(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static DatabaseSnapshot snapshot() throws SQLException {
        try (Connection c = admin(dbName)) {
            return DatabaseSnapshot.take(c, tables(c));
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
