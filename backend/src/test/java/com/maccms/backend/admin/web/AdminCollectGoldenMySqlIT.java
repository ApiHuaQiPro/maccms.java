package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.collect.application.CollectAdminServices;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;

/**
 * B17 采集配置管理的真实 MySQL 对照（./mvnw verify -Pmysql-it；25 文档第 8 节）。外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; →
 * 导入 install.sql → 取表结构指纹 → 写入与黄金脚本相同的管理员与分类 → 启动打开了可写数据源与后台的应用（旧配置目录在临时目录，
 * {@code extra/maccms.php} 取自黄金）：
 * <ol>
 * <li>复放 PHP 8.0 与 7.3 的黄金（{@code admin_collect_golden.php}）：每个场景清空四张表、复位自增计数、写入场景的行与预置的 bind.php，
 * 换新会话；每个请求前把应用的时钟拨到 PHP 请求开始的秒，发同样的请求，比较状态码、JSON 字节、页面结构（{@code AdminContentHtml}；
 * 表单步骤另比较打开的表单页）、导出的正文与文件名、bind.php 的变化（逐字节）与四张表的全部行（{@code lastdate} 按请求的时间窗比较）。
 * 有意差异（25 文档第 9 节）按 {@link #JAVA} 等断言新版的结果。</li>
 * <li>不发起网络请求：{@code collect/test} 指向本机一个监听端口时，返回固定提示且端口上没有连接。</li>
 * <li>并发：32 个线程同时绑定 32 个不同的键，一个都不丢（bind.php 在进程内锁里读改写）。</li>
 * <li>回滚演练：库快照 + bind.php 快照 → 本批全部写操作 → {@code DmlRestore} 只用 DML 恢复、bind.php 用快照覆盖 → diff 为空。</li>
 * </ol>
 * 结束时表结构指纹不变，删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCollectGoldenMySqlIT {

    static final String PHP80 = "admin/collect/php80-golden.json";
    static final String PHP73 = "admin/collect/php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    /** 跟踪的表与主键（与黄金脚本的 TABLES 相同）。 */
    static final Map<String, String> TABLES = new LinkedHashMap<>();
    private static final MutableClock CLOCK = new MutableClock(Instant.now().getEpochSecond());

    static {
        TABLES.put("mac_collect", "collect_id");
        TABLES.put("mac_cj_node", "nodeid");
        TABLES.put("mac_cj_content", "id");
        TABLES.put("mac_cj_history", "md5");
    }

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static Map<String, Map<String, String>> columnKinds;
    private static ConfigurableApplicationContext app;
    private static MockMvc mvc;
    private static Path legacy;
    private static Path bindFile;

    // ------------------------------------------------------------------ 有意差异（25 文档第 9 节）

    /** 键为 {@code 场景/步骤}（两版都适用）或 {@code 80:…}、{@code 73:…}；值为新版的观察结果。 */
    static final Map<String, String> JAVA = new HashMap<>();
    /** 旧版写了库、新版拒绝不写的步骤：新版这一步之后的表与上一步相同。 */
    static final Set<String> JAVA_NO_WRITE = new HashSet<>();
    /**
     * 页面的同一规则（D-B13-19）：旧模板原样输出库里的文字，其中的标签被浏览器当作标签解析、双引号截断属性值；新版按浏览器看到的文字转义输出。
     * 种子里含标签与引号的取值：采集源 2 的名称 {@code json源<b>}、采集源 3 的名称与地址、规则 2 的名称、规则 1 自定义字段的名称。
     */
    static final List<String[]> JAVA_GLOBAL = List.of(
            new String[] {"text a 【json源 / ", "text a 【json源<b>】https://json.example.test/api?ac=list&x=中文 空格 / "},
            new String[] {"【json源】https://json.example.test/api?ac=list&x=中文 空格", "【json源<b>】https://json.example.test/api?ac=list&x=中文 空格"},
            new String[] {"【Actor\"Q\"&】http://a.example.test/provide/?k=", "【Actor\"Q\"&】http://a.example.test/provide/?k=<x>"},
            new String[] {"| 文章规则 |", "| 文章<b>规则</b> |"},
            new String[] {"input collect_name type=text value=Actor id=collect_name", "input collect_name type=text value=Actor\"Q\"& id=collect_name"},
            new String[] {"input collect_param type=text value=a=b&c= id=collect_param", "input collect_param type=text value=a=b&c=\"d\" id=collect_param"});
    /** 只对某几步的页面做的替换：规则 1 的自定义字段名称 {@code 主演"x"}（保存过一次以后库里已是旧版截断后的 {@code 主演}，之后的页面两边相同）。 */
    static final Map<String, List<String[]>> JAVA_PATCH = Map.of(
            "cj-info/edit", List.<String[]>of(new String[] {"input data[customize_config][name][] type=text value=主演 placeholder=",
                "input data[customize_config][name][] type=text value=主演\"x\" placeholder="}),
            "cj-save/edit", List.<String[]>of(new String[] {"input data[customize_config][name][] type=text value=主演 placeholder=",
                "input data[customize_config][name][] type=text value=主演\"x\" placeholder="}));

    static {
        String paramErr = "status=200 json={\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        // D-B13-3（同一规则）：旧版在 MySQL 严格模式下报错（整页 500）或把数组静默丢掉的取值，新版"参数错误"、不写
        for (String step : List.of("collect-save-bad/name-too-long", "collect-save-bad/type-bad", "cj-add/add-form",
                "cj-save-bad/name-too-long", "cj-save-bad/diy-scalar", "cj-save-bad/array-value", "cj-save-bad/no-data",
                "cj-import/nested")) {
            JAVA.put(step, paramErr);
        }
        JAVA_NO_WRITE.add("73:cj-save-bad/diy-scalar");
        JAVA_NO_WRITE.add("cj-save-bad/array-value");
        JAVA_NO_WRITE.add("cj-save-bad/no-data");
        // D-B13-4（同一规则）：ids 每项必须是十进制数字（旧版交给 MySQL 隐式转换：abc 当 0 什么都不删；1abc 当 1，删掉了规则 1）
        JAVA.put("collect-del-bad/bad", paramErr);
        JAVA.put("cj-del-bad/bad", paramErr);
        JAVA_NO_WRITE.add("cj-del-bad/bad");
    }

    // ------------------------------------------------------------------ 准备与清理

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
                // 与黄金脚本的 base_seed() 相同
                st.execute("SET SESSION sql_mode = ''");
                st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                        + "(1, 'admin', '" + md5("admin888") + "', 1, ''), (2, 'editor', '" + md5("ed pass") + "', 1, ',collect/index,cj/index,')");
                st.execute("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
                        + "(1, '电影', 'dianying', 1, 1, 0, 1), (2, '连续剧', 'lianxuju', 2, 1, 0, 1), (6, 'A&B<型>\"', 'ab', 3, 2, 0, 0)");
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            columnKinds = columnKinds(c);
        }
        JsonNode g = load(PHP80);
        Path dir = Files.createTempDirectory("maccms-b17-legacy");
        legacy = dir.resolve("application");
        Path extra = Files.createDirectories(legacy.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        Files.write(extra.resolve("maccms.php"), Base64.getDecoder().decode(g.get("maccmsPhp").asString()));
        bindFile = extra.resolve("bind.php");
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + legacy,
                "--maccms.upload.dir=" + dir.resolve("upload"), "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("b17TestClock", CLOCK))
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
        if (legacy != null) {
            deleteTree(legacy.getParent());
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

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void scenariosMatchPhpGolden() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String version : List.of(PHP80, PHP73)) {
            JsonNode golden = load(version);
            String tag = version.equals(PHP80) ? "80" : "73";
            String only = System.getProperty("golden.only", "");
            for (JsonNode s : golden.get("scenarios")) {
                if (!only.isEmpty() && java.util.Arrays.stream(only.split(",")).noneMatch(o -> s.get("label").asString().startsWith(o))) {
                    continue;
                }
                compared += replay(tag, s, problems);
            }
        }
        System.out.println("[mysql-it] admin collect golden steps compared: " + compared);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-collect-golden-problems.txt"), String.join("\n\n", problems), StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA 断言），详见 target/admin-collect-golden-problems.txt").isEmpty();
        assertThat(compared).isGreaterThan(100);
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        prepare(s);
        Map<String, List<Map<String, String>>> lastPhp = rows(s.get("seed"));
        Map<String, List<Map<String, String>>> java0 = javaRows();
        if (!java0.equals(lastPhp)) {
            problems.add(tag + ":" + label + " 场景开始时的行与 PHP 不同\n  php:  " + lastPhp + "\n  java: " + java0);
            return 0;
        }
        byte[] lastBind = bindNow();
        Set<String> accepted = new HashSet<>();
        String sid = null;
        String token = "";
        int n = 0;
        for (JsonNode st : s.get("steps")) {
            String key = label + "/" + st.get("label").asString();
            JsonNode req = st.get("request");
            long started = st.get("started").asLong();
            long after = st.get("after").asLong();
            CLOCK.set(started);
            if (st.has("page")) {
                MockHttpServletResponse page = send(sid, "GET", req.get("path").asString(), req.get("query").asString(), null, false);
                sid = cookie(page, sid);
                String html = page.getContentAsString(StandardCharsets.UTF_8);
                token = token(html, token);
                String want = patched(AdminContentGoldenMySqlIT.observe(st.get("page").get("status").asInt(), null,
                        new String(Base64.getDecoder().decode(st.get("page").get("body").asString()), StandardCharsets.UTF_8)), tag, key, true);
                String got = AdminContentGoldenMySqlIT.observe(page.getStatus(), null, html);
                if (!want.equals(got)) {
                    problems.add(tag + ":" + key + "（表单页）\n  expected: " + want + "\n  actual:   " + got);
                }
            }
            MockHttpServletResponse r;
            if (st.has("upload")) {
                r = upload(sid, req, st.get("upload"));
            } else {
                r = send(sid, req.get("method").asString(), req.get("path").asString(), req.get("query").asString().replace("{token}", token),
                        req.get("post").asString().replace("{token}", token), req.get("ajax").asBoolean());
            }
            sid = cookie(r, sid);
            String body = r.getContentAsString(StandardCharsets.UTF_8);
            token = token(body, token);
            String actual = observe(r.getStatus(), r.getHeader("Location"), disposition(r.getHeader("Content-Disposition")), body);
            String expected = JAVA.containsKey(tag + ":" + key) ? JAVA.get(tag + ":" + key)
                    : JAVA.containsKey(key) ? JAVA.get(key) : patched(phpObservation(st), tag, key, false);
            if (!expected.equals(actual)) {
                problems.add(tag + ":" + key + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
            // bind.php：false 为这一步没有变化，null 为被删除，否则为这一步之后的内容
            byte[] bindNow = bindNow();
            JsonNode pb = st.get("bind");
            boolean phpChanged = !(pb.isBoolean() && !pb.asBoolean());
            boolean javaChanged = !java.util.Arrays.equals(lastBind, bindNow);
            if (phpChanged != javaChanged || (phpChanged && !java.util.Arrays.equals(pb.isNull() ? null
                    : Base64.getDecoder().decode(pb.asString()), bindNow))) {
                problems.add(tag + ":" + key + " bind.php\n  expected: " + (phpChanged ? (pb.isNull() ? "<deleted>"
                        : new String(Base64.getDecoder().decode(pb.asString()), StandardCharsets.UTF_8)) : "<unchanged>")
                        + "\n  actual:   " + (javaChanged ? (bindNow == null ? "<deleted>" : new String(bindNow, StandardCharsets.UTF_8))
                        : "<unchanged>"));
            }
            lastBind = bindNow;
            boolean noWrite = JAVA_NO_WRITE.contains(tag + ":" + key) || JAVA_NO_WRITE.contains(key);
            Map<String, List<Map<String, String>>> php = noWrite ? lastPhp : rows(st.get("tables"));
            Map<String, List<Map<String, String>>> java = javaRows();
            for (String t : TABLES.keySet()) {
                List<String> diff = diffRows(php.get(t), java.get(t), started, after, accepted);
                if (!diff.isEmpty()) {
                    problems.add(tag + ":" + key + " 表 " + t + "：\n  " + String.join("\n  ", diff));
                }
            }
            lastPhp = php;
            n++;
        }
        return n;
    }

    /** 场景开始：清空四张表、复位自增计数，写入场景的行；bind.php 为场景预置的内容（null 为没有这个文件）。 */
    private static void prepare(JsonNode s) throws Exception {
        seed(s.get("seed"));
        JsonNode b = s.get("bind0");
        if (b.isNull()) {
            Files.deleteIfExists(bindFile);
        } else {
            Files.write(bindFile, Base64.getDecoder().decode(b.asString()));
        }
        // 写回时按修改时间与大小判断是否重读：拨到未来，避免同一秒里内容不同、大小相同的文件被当作没变
        if (Files.exists(bindFile)) {
            Files.setLastModifiedTime(bindFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000L * SEQ++));
        }
    }

    private static long SEQ = 1;

    /** 对 PHP 的观察结果做页面的同一规则替换（只对页面，JSON 与文件逐字节比较）。 */
    private static String patched(String o, String tag, String key, boolean page) {
        if (o == null || !(o.contains(" page ") || o.contains(" ajaxpage "))) {
            return o;
        }
        for (String[] fix : JAVA_GLOBAL) {
            o = o.replace(fix[0], fix[1]);
        }
        for (String[] fix : JAVA_PATCH.getOrDefault(key, List.of())) {
            o = o.replace(fix[0], fix[1]);
        }
        return o;
    }

    static String phpObservation(JsonNode st) {
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        String location = st.get("location").isNull() ? null : st.get("location").asString();
        String disposition = st.get("disposition").isNull() ? null
                : new String(Base64.getDecoder().decode(st.get("disposition").asString()), StandardCharsets.UTF_8);
        return observe(st.get("status").asInt(), location, disposition, body);
    }

    /** B13 的观察结果；下载（导出）另带 Content-Disposition 与正文原文。 */
    static String observe(int status, String location, String disposition, String body) {
        if (disposition != null) {
            return "status=" + status + " disposition=" + disposition + " body=" + body;
        }
        return AdminContentGoldenMySqlIT.observe(status, location, body);
    }

    /** 新版的 Content-Disposition 按字节写出 UTF-8（Servlet 的字符串里每个字符是一个字节）。 */
    private static String disposition(String header) {
        return header == null ? null : new String(header.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ② 不发起网络请求

    /**
     * 旧版的"测试"按 {@code cjurl} 发起 HTTP 请求；新版（E01 只做本地版本）返回固定提示。地址指向本机一个正在监听的端口：请求返回后 2 秒内
     * 端口上没有任何连接。
     */
    @Test
    @Order(2)
    void collectTestNeverConnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            String sid = login();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/api.php/provide/vod/";
            MockHttpServletResponse r = send(sid, "POST", "/admin/collect/test", "",
                    "cjurl=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8) + "&cjflag=&ac=list", true);
            assertThat(r.getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo("{\"code\":1001,\"msg\":\"" + AdminCollectActions.TEST_REFUSED + "\"}");
            boolean connected;
            try {
                server.accept().close();
                connected = true;
            } catch (SocketTimeoutException e) {
                connected = false;
            }
            assertThat(connected).as("collect/test 没有连接资源站").isFalse();
        }
    }

    // ------------------------------------------------------------------ ③ 并发绑定

    /** 32 个管理员同时绑定 32 个不同的远程分类：bind.php 在进程内的文件锁里读改写，结束时 32 个键都在、原有的键不变、文件能读回。 */
    @Test
    @Order(3)
    void concurrentBindsNeverLoseAnUpdate() throws Exception {
        prepare(scenario("bind"));
        CollectAdminServices s = app.getBean(CollectAdminServices.class);
        String flag = md5("http://concurrent.example.test/");
        var outcome = ConcurrentWrites.run(32, i -> s.binds().bind(form("col=" + flag + "_" + i + "&val=" + (1 + i % 2))).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(32);
        var bind = s.binds().bindings();
        for (int i = 0; i < 32; i++) {
            assertThat(com.maccms.backend.collect.application.CollectBindings.localTypeId(bind, flag + "_" + i)).isEqualTo(1 + i % 2);
        }
        assertThat(com.maccms.backend.collect.application.CollectBindings.localTypeId(bind, md5("http://res.example.test/api.php/provide/vod/") + "_1"))
                .isEqualTo(1);
        assertThat(bind.entries()).hasSize(6 + 32);
    }

    /** 同时删除同一批规则：结束时规则、内容与采集历史都已删除，没有出错（旧版 MyISAM 没有事务，新版按旧版的顺序逐步删，重复删除是空操作）。 */
    @Test
    @Order(4)
    void concurrentDeletesOfTheSameNodesAreIdempotent() throws Exception {
        prepare(scenario("cj-del"));
        CollectAdminServices s = app.getBean(CollectAdminServices.class);
        var outcome = ConcurrentWrites.run(12, i -> s.nodes().delete(form("ids=1,2")).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM mac_cj_node WHERE nodeid IN (1, 2)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_cj_content")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_cj_history")).as("只剩不属于任何规则内容的那一条").isEqualTo(1);
    }

    // ------------------------------------------------------------------ ④ 回滚演练

    /**
     * 快照（四张表 + bind.php）→ 经各服务做一遍本批的写入（采集源新增/编辑/删除、绑定与清除绑定、规则新增（导入）/编辑/删除）→ 库用
     * {@code DmlRestore} 只用 DML 恢复、bind.php 用快照覆盖 → 与快照逐行 diff 为空、文件逐字节相同 → 表结构指纹不变。
     */
    @Test
    @Order(5)
    void rollbackDrillRestoresEveryB17WriteWithDmlAndFileSnapshot() throws Exception {
        JsonNode sc = scenario("cj-del");
        prepare(sc);
        List<String> tables = List.copyOf(TABLES.keySet());
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        byte[] bindBefore = Files.readAllBytes(bindFile);
        CollectAdminServices s = app.getBean(CollectAdminServices.class);
        CLOCK.set(Instant.now().getEpochSecond());
        assertThat(s.sources().save(form("collect_id=&collect_name=drill&collect_url=http://d.test/")).ok()).isTrue();
        assertThat(s.sources().save(form("collect_id=2&collect_name=drill2&collect_url=http://d2.test/")).ok()).isTrue();
        assertThat(s.sources().delete(form("ids=3,4")).ok()).isTrue();
        assertThat(s.binds().bind(form("col=" + md5("http://d.test/") + "_1&val=2")).ok()).isTrue();
        assertThat(s.binds().clear(form("cjflag=" + md5("http://res.example.test/api.php/provide/vod/")))).isTrue();
        String exported = s.nodes().export("1".getBytes(StandardCharsets.UTF_8), false).orElseThrow().body();
        assertThat(s.nodes().importNode(exported.getBytes(StandardCharsets.US_ASCII)).ok()).isTrue();
        assertThat(s.nodes().save(java.util.Map.of("nodeid", "3".getBytes(StandardCharsets.UTF_8), "name", "drill".getBytes(StandardCharsets.UTF_8),
                "sourcetype", "1".getBytes(StandardCharsets.UTF_8)), form("urlpage1=http://x.test/")).ok()).isTrue();
        assertThat(s.nodes().delete(form("ids=1")).ok()).isTrue();
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, tables);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, changed);
            assertThat(plan).isNotEmpty().allMatch(x -> x.sql().startsWith("INSERT") || x.sql().startsWith("UPDATE")
                    || x.sql().startsWith("DELETE"));
            DmlRestore.apply(c, plan);
            SnapshotDiff diff = SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables));
            assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
            System.out.println("[mysql-it] B17 rollback drill restored with " + plan.size() + " DML statements");
        }
        assertThat(Files.readAllBytes(bindFile)).isNotEqualTo(bindBefore);
        Files.write(bindFile, bindBefore);
        assertThat(Files.readAllBytes(bindFile)).isEqualTo(bindBefore);
        assertThat(s.binds().bindings().entries()).hasSize(6);
    }

    // ------------------------------------------------------------------ 请求

    private static String login() throws Exception {
        MockHttpServletResponse r = send(null, "POST", "/admin/index/login", "", "admin_name=admin&admin_pwd=admin888", true);
        return cookie(r, null);
    }

    private static MockHttpServletResponse send(String sid, String method, String path, String query, String post, boolean ajax)
            throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        b.header("User-Agent", "Mozilla/5.0 (golden)");
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded").content((post == null ? "" : post).getBytes(StandardCharsets.ISO_8859_1));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private static MockHttpServletResponse upload(String sid, JsonNode req, JsonNode up) throws Exception {
        MockMultipartFile file = new MockMultipartFile(up.get("field").asString(), up.get("filename").asString(), "text/plain",
                Base64.getDecoder().decode(up.get("content").asString()));
        var b = MockMvcRequestBuilders.multipart(java.net.URI.create(E + req.get("path").asString())).file(file);
        b.header("X-Requested-With", "XMLHttpRequest");
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
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

    private static final Pattern TOKEN = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]+)\"");

    private static String token(String html, String current) {
        Matcher m = TOKEN.matcher(html);
        return m.find() ? m.group(1) : current;
    }

    private static PhpForm form(String urlencoded) {
        return AdminContentActions.form(AdminParams.parse(urlencoded.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static JsonNode scenario(String label) {
        for (JsonNode s : load(PHP80).get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException(label);
    }

    // ------------------------------------------------------------------ 表

    /** 清空跟踪的表、复位自增计数（一次性库上的测试夹具，与黄金脚本相同），写入场景的行。 */
    private static void seed(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String t : TABLES.keySet()) {
                    st.execute("DELETE FROM " + t);
                    if (!t.equals("mac_cj_history")) {
                        st.execute("ALTER TABLE " + t + " AUTO_INCREMENT = 1");
                    }
                }
            }
            for (Map.Entry<String, JsonNode> e : seed.properties()) {
                String table = e.getKey();
                for (JsonNode row : e.getValue()) {
                    List<String> cols = new ArrayList<>();
                    row.properties().forEach(x -> cols.add(x.getKey()));
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < cols.size(); i++) {
                            String v = row.get(cols.get(i)).asString();
                            ps.setString(i + 1, "num".equals(columnKinds.get(table).get(cols.get(i))) ? v
                                    : new String(HexFormat.of().parseHex(v), StandardCharsets.UTF_8));
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    static Map<String, List<Map<String, String>>> javaRows() throws SQLException {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> t : TABLES.entrySet()) {
                List<Map<String, String>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + t.getKey() + " ORDER BY " + t.getValue())) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnLabel(i);
                            if ("num".equals(columnKinds.get(t.getKey()).get(col))) {
                                row.put(col, rs.getString(i));
                            } else {
                                byte[] b = rs.getBytes(i);
                                row.put(col, HexFormat.of().formatHex(b == null ? new byte[0] : b));
                            }
                        }
                        rows.add(row);
                    }
                }
                out.put(t.getKey(), rows);
            }
        }
        return out;
    }

    private static Map<String, List<Map<String, String>>> rows(JsonNode tables) {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (String t : TABLES.keySet()) {
            List<Map<String, String>> list = new ArrayList<>();
            JsonNode rows = tables.get(t);
            if (rows != null) {
                for (JsonNode r : rows) {
                    Map<String, String> row = new LinkedHashMap<>();
                    r.properties().forEach(e -> row.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
                    list.add(row);
                }
            }
            // 种子按写入的顺序，各步骤之后的行按主键（ORDER BY）：统一按主键排序（整数按数值，md5 按十六进制文字）
            String pk = TABLES.get(t);
            boolean num = "num".equals(columnKinds.get(t).get(pk));
            list.sort(Comparator.comparing((Map<String, String> r) -> num ? String.format("%020d", Long.parseLong(r.get(pk)))
                    : new String(HexFormat.of().parseHex(r.get(pk)), StandardCharsets.UTF_8)));
            out.put(t, list);
        }
        return out;
    }

    /** 逐行比较；规则的 {@code lastdate}（{@code time()}）：PHP 的值落在请求的 [started, after] 秒内、新版的值等于拨好的秒时视为相同。 */
    private static List<String> diffRows(List<Map<String, String>> php, List<Map<String, String>> java, long started, long after,
                                         Set<String> accepted) {
        List<String> out = new ArrayList<>();
        if (php.size() != java.size()) {
            out.add("行数 php=" + php.size() + " java=" + java.size() + "\n  php:  " + php + "\n  java: " + java);
            return out;
        }
        for (int i = 0; i < php.size(); i++) {
            Map<String, String> p = new LinkedHashMap<>(php.get(i));
            Map<String, String> j = new LinkedHashMap<>(java.get(i));
            if (p.containsKey("lastdate") && !p.get("lastdate").equals(j.get("lastdate"))) {
                long pv = Long.parseLong(p.get("lastdate"));
                long jv = Long.parseLong(j.get("lastdate"));
                // 之前的步骤里已经认定过的同一对取值（那一步写入的时间）照样视为相同
                String pair = p.get("nodeid") + ":" + pv + ":" + jv;
                if ((pv >= started && pv <= after && jv == started) || accepted.contains(pair)) {
                    accepted.add(pair);
                    p.remove("lastdate");
                    j.remove("lastdate");
                }
            }
            if (!p.equals(j)) {
                out.add("第 " + (i + 1) + " 行\n    php:  " + text(p) + "\n    java: " + text(j));
            }
        }
        return out;
    }

    /** 十六进制的文本列解码后显示（只用于差异报告）。 */
    private static String text(Map<String, String> row) {
        Map<String, String> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, v != null && v.matches("([0-9a-f]{2})+") && !v.matches("[0-9]+")
                ? new String(HexFormat.of().parseHex(v), StandardCharsets.UTF_8) : v));
        return m.toString();
    }

    private static byte[] bindNow() throws IOException {
        return Files.exists(bindFile) ? Files.readAllBytes(bindFile) : null;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ 工具

    static JsonNode load(String resource) {
        try (InputStream in = AdminCollectGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Map<String, String>> columnKinds(Connection c) throws SQLException {
        Map<String, Map<String, String>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE()"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString(3);
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2),
                        type.contains("int") || type.equals("decimal") ? "num" : "text");
            }
        }
        return out;
    }

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
