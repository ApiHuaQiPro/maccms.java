package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.admin.AdminContentHtml;
import com.maccms.backend.comment.application.CommentAdminService;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AnnexAdminService;
import com.maccms.backend.content.application.ContentAdminServices;
import com.maccms.backend.content.application.ContentCounterService;
import com.maccms.backend.content.application.CounterTarget;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B13 后台内容管理的真实 MySQL 对照（./mvnw verify -Pmysql-it；22 文档第 9 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 取表结构指纹 → 按场景的旧配置（黄金里记录的
 * 完整 extra/maccms.php）各启动一个打开了可写数据源与后台的应用上下文 → 逐个复放 PHP 8.0 与 7.3 的黄金
 * （admin_content_golden.php）：每个场景先把跟踪的各表清空、复位自增计数（与黄金脚本相同的测试夹具）并写入场景的行，把临时站点根目录的
 * upload/ 重置为场景的文件，换新的会话；每个请求前把应用的时钟拨到 PHP 请求开始的秒，按同样的请求（方法、路径、查询串、正文字节、
 * Ajax 头）发给新版，比较状态码、Location、JSON 字节、页面结构（{@link AdminContentHtml}）、请求后各表的全部行与 upload/ 的文件。
 * 写入的时间列：PHP 的值落在请求的 [started, after] 秒内、新版的值等于拨好的秒时视为相同。有意差异（22 文档第 10 节）按 {@link #JAVA}
 * 断言新版的结果。之后是并发编辑（②）与回滚演练（③）。结束时表结构指纹不变，删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminContentGoldenMySqlIT {

    static final String PHP80 = "admin/content/php80-golden.json";
    static final String PHP73 = "admin/content/php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    /** 跟踪的表与主键（与黄金脚本的 TABLES 相同）。 */
    static final Map<String, String> TABLES = tables();
    private static final MutableClock CLOCK = new MutableClock(Instant.now().getEpochSecond());

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static Map<String, Map<String, String>> columnKinds;
    private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();
    private static final List<Path> TEMP_DIRS = new ArrayList<>();

    /** 一组旧配置对应的应用上下文与临时站点根目录。 */
    record Variant(ConfigurableApplicationContext app, MockMvc mvc, Path legacyDir, Path siteRoot) {
    }

    private static Map<String, String> tables() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mac_type", "type_id");
        m.put("mac_vod", "vod_id");
        m.put("mac_art", "art_id");
        m.put("mac_topic", "topic_id");
        m.put("mac_actor", "actor_id");
        m.put("mac_role", "role_id");
        m.put("mac_website", "website_id");
        m.put("mac_link", "link_id");
        m.put("mac_comment", "comment_id");
        m.put("mac_gbook", "gbook_id");
        m.put("mac_annex", "annex_id");
        m.put("mac_vod_search", "search_key");
        return m;
    }

    // ------------------------------------------------------------------ 有意差异（22 文档第 10 节）：场景/步骤 → 新版的观察结果

    /**
     * 键为 {@code 场景/步骤}（两版 PHP 都适用）或 {@code 80:场景/步骤}、{@code 73:场景/步骤}（只对该版）。值为新版的观察结果（与
     * {@link #observe} 的格式相同），按值逐字比较；新版这一步之后的表与文件仍须与 PHP 相同（PHP 报错时没有写库）。
     */
    static final Map<String, String> JAVA = new HashMap<>();

    /** 有意差异里只差页面局部的步骤：对 PHP 的观察结果依次做文字替换后再比较（键为 {@code 场景/步骤}）。 */
    static final Map<String, List<String[]>> JAVA_PATCH = new HashMap<>();

    /** 有意差异里旧版写了库、新版拒绝不写的步骤（键同 {@link #JAVA}；只用于场景的最后一步）。 */
    static final java.util.Set<String> JAVA_NO_WRITE = new java.util.HashSet<>();

    static {
        String paramErr = "status=200 json={\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        // D-B13-3：旧版在 MySQL 严格模式下报错（整页 500）的取值，新版"参数错误"、不写库
        for (String step : List.of("link-save/sort-bad", "link-save/name-too-long", "link-save/name-emoji")) {
            JAVA.put(step, paramErr);
        }
        // D-B13-4：ids 每项必须是十进制数字（旧版交给 MySQL 隐式转换，abc 当 0，"删除成功"）
        JAVA.put("link-del/bad", paramErr);
        // D-B13-4：批量修改没有 ids[]（旧版 foreach 非数组后以未定义的 $res 输出 code=1、msg=null）
        JAVA.put("link-batch/batch-empty", paramErr);
        // D-B13-3：批量修改的第二个分类缺少模板参数（null 写进 NOT NULL 列）、新增专题缺少 TEXT 列（严格模式没有默认值），旧版 500
        JAVA.put("type-batch/batch", paramErr);
        JAVA.put("topic-save/new", paramErr);
        // D-B13-3：保存时分类不存在（旧版取 $type_list[分类]['type_pid'] 报 PHP 错误，整页 500）
        JAVA.put("actor-save/type-missing", paramErr);
        JAVA.put("vod-save/type-missing", paramErr);
        // D-B13-3：有播放组而没有播放地址（旧版 PHP 8 的 join('$$$', '') 报错，整页 500；PHP 7.3 只告警、地址写成空串照常保存）
        JAVA.put("vod-save/from-no-url", paramErr);
        JAVA_NO_WRITE.add("73:vod-save/from-no-url");
        // D-B13-10：旧模板内层服务器组循环覆盖了 $key，播放/下载地址文本框的 id 写成"前缀 + 最后一个服务器组"（多组时重复）；新版为组序号
        JAVA_PATCH.put("vod-info/edit", List.of(
                new String[] {"id textarea#vod_play_urlserver1 / id textarea#vod_play_urlserver1",
                    "id textarea#vod_play_url1 / id textarea#vod_play_url3"},
                new String[] {"id textarea#vod_down_urlserver1", "id textarea#vod_down_url1"}));
        // D-B13-9：分集剧情页没有这条视频（旧版取 null 的下标报 PHP 错误，整页 500），新版"获取失败"
        JAVA.put("vod-iplot/view-new", "status=200 json={\"code\":0,\"msg\":\"获取失败\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
    }

    // ------------------------------------------------------------------ 准备与清理

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + HexFormat.of().formatHex(rnd);
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
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            columnKinds = columnKinds(c);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (Variant v : VARIANTS.values()) {
            v.app().close();
        }
        for (Path dir : TEMP_DIRS) {
            deleteTree(dir);
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            Map<String, String> schemaNow;
            try (Connection c = admin(dbName)) {
                schemaNow = SchemaFingerprint.of(c, dbName);
            }
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            // 自增计数不在指纹内：场景夹具复位的只是计数值
            assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
        }
    }

    /** 场景的旧配置（黄金记录的完整 extra/maccms.php）对应的应用上下文，没有就启动一个。 */
    static Variant variant(String maccmsPhp) throws Exception {
        Variant v = VARIANTS.get(maccmsPhp);
        if (v != null) {
            return v;
        }
        Path dir = Files.createTempDirectory("maccms-b13-legacy");
        TEMP_DIRS.add(dir);
        Path extra = Files.createDirectories(dir.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        Files.writeString(extra.resolve("maccms.php"), maccmsPhp, StandardCharsets.UTF_8);
        Path site = Files.createTempDirectory("maccms-b13-site");
        TEMP_DIRS.add(site);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + dir,
                "--maccms.upload.dir=" + site.resolve("upload"),
                "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        ConfigurableApplicationContext app;
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("b13TestClock", CLOCK))
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
        v = new Variant(app, mvc, dir, site);
        VARIANTS.put(maccmsPhp, v);
        return v;
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
        System.out.println("[mysql-it] admin content golden steps compared: " + compared);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-content-golden-problems.txt"), String.join("\n\n", problems),
                StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA 断言），详见 target/admin-content-golden-problems.txt")
                .isEmpty();
        assertThat(compared).isGreaterThan(0);
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        resetFiles(v.siteRoot(), s.get("filesSeed"));
        Map<String, List<Map<String, Object>>> lastPhp = rows(s.get("rows0"));
        Map<String, List<Map<String, Object>>> java0 = javaRows();
        if (!java0.equals(lastPhp)) {
            problems.add(tag + ":" + label + " 场景开始时的行与 PHP 不同\n  php:  " + lastPhp + "\n  java: " + java0);
            return 0;
        }
        Map<String, Object> lastPhpFiles = files(s.get("files0"));
        java.util.Set<String> acceptedTimes = new java.util.HashSet<>();
        // 上传的文件名（旧版 md5(microtime(true))，新版 md5(毫秒.随机数)）两边各自生成：按出现顺序把 PHP 的名称对应到新版的名称
        Map<String, String> names = new LinkedHashMap<>();
        String sid = null;
        String phpToken = null;
        String javaToken = null;
        int n = 0;
        for (JsonNode st : s.get("steps")) {
            String step = st.get("label").asString();
            String key = label + "/" + step;
            if (st.has("sql")) {
                try (Connection c = admin(dbName); Statement x = c.createStatement()) {
                    x.execute(st.get("sql").asString());
                }
                lastPhp = merge(lastPhp, st.get("tables"));
                continue;
            }
            long started = st.get("started").asLong();
            long after = st.get("after").asLong();
            CLOCK.set(started);
            MockHttpServletResponse r = send(v, sid, st.get("request"), phpToken, javaToken);
            sid = cookie(r, sid);
            String body = r.getContentAsString(StandardCharsets.UTF_8);
            // 表单令牌：PHP 与新版各自签发；下一个请求里 PHP 的令牌换成新版这一边的（比较时令牌值一律记为 TOKEN）
            String pt = token(phpBody(st));
            if (pt != null) {
                phpToken = pt;
            }
            String jt = token(body);
            if (jt != null) {
                javaToken = jt;
            }
            pairUploadNames(phpBody(st), body, names);
            String actual = observe(r.getStatus(), r.getHeader("Location"), body);
            String expected = JAVA.containsKey(tag + ":" + key) ? JAVA.get(tag + ":" + key)
                    : JAVA.containsKey(key) ? JAVA.get(key) : phpObservation(st);
            if (expected != null && JAVA_PATCH.containsKey(key)) {
                for (String[] fix : JAVA_PATCH.get(key)) {
                    expected = expected.replace(fix[0], fix[1]);
                }
            }
            if (expected != null) {
                expected = requestTime(renamed(expected, names), started, after);
            }
            if (expected != null && key.equals("upload/test")) {
                // 临时文件路径因机器而异
                expected = expected.replaceFirst("：.*$", "：PATH");
                actual = actual.replaceFirst("：.*$", "：PATH");
            }
            if (expected != null && !expected.equals(actual)) {
                problems.add(tag + ":" + key + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
            // PHP 写了库而新版按有意差异拒绝（只用于场景的最后一步）：新版这一步之后的表应与上一步相同
            boolean noWrite = JAVA_NO_WRITE.contains(tag + ":" + key) || JAVA_NO_WRITE.contains(key);
            Map<String, List<Map<String, Object>>> php = noWrite ? lastPhp
                    : renamedRows(merge(lastPhp, st.get("tables")), names);
            Map<String, List<Map<String, Object>>> java = javaRows();
            for (String t : TABLES.keySet()) {
                List<String> diff = diffRows(php.get(t), java.get(t), started, after, acceptedTimes);
                if (!diff.isEmpty()) {
                    problems.add(tag + ":" + key + " 表 " + t + "：\n  " + String.join("\n  ", diff));
                }
            }
            lastPhp = php;
            Map<String, Object> phpFiles = st.get("files") == null || st.get("files").isNull() ? lastPhpFiles
                    : renamedFiles(files(st.get("files")), names);
            Map<String, Object> javaFiles = listFiles(v.siteRoot());
            if (!phpFiles.equals(javaFiles)) {
                problems.add(tag + ":" + key + " upload/ 文件：\n  php:  " + phpFiles + "\n  java: " + javaFiles);
            }
            lastPhpFiles = phpFiles;
            n++;
        }
        return n;
    }

    private static final Pattern UPLOAD_NAME = Pattern.compile("[0-9]{8}-[0-9]+\\\\?/([0-9a-f]{32})");

    /** 两边响应里依次出现的上传文件名（{@code 日期-序号/md5}）按顺序配对，记进 {@code names}（PHP → 新版）。 */
    static void pairUploadNames(String php, String java, Map<String, String> names) {
        if (php == null || java == null) {
            return;
        }
        java.util.regex.Matcher p = UPLOAD_NAME.matcher(php);
        java.util.regex.Matcher j = UPLOAD_NAME.matcher(java);
        while (p.find() && j.find()) {
            names.putIfAbsent(p.group(1), j.group(1));
        }
    }

    private static final Pattern CTIME = Pattern.compile("\"ctime\":([0-9]+)");

    /** 上传结果里的请求时间（旧 request()->time()）：PHP 的值落在 [started, after] 内时记为新版拨好的秒 started（同表里的时间列）。 */
    static String requestTime(String s, long started, long after) {
        java.util.regex.Matcher m = CTIME.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long t = Long.parseLong(m.group(1));
            m.appendReplacement(sb, "\"ctime\":" + (t >= started && t <= after ? started : t));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String renamed(String s, Map<String, String> names) {
        for (Map.Entry<String, String> e : names.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    /** 行里的文字列是十六进制编码的字节：名称同样按十六进制替换。 */
    static Map<String, List<Map<String, Object>>> renamedRows(Map<String, List<Map<String, Object>>> rows,
                                                              Map<String, String> names) {
        if (names.isEmpty()) {
            return rows;
        }
        Map<String, String> hex = new LinkedHashMap<>();
        names.forEach((k, v) -> hex.put(HexFormat.of().formatHex(k.getBytes(StandardCharsets.US_ASCII)),
                HexFormat.of().formatHex(v.getBytes(StandardCharsets.US_ASCII))));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        rows.forEach((t, list) -> {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> r : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                r.forEach((c, v) -> row.put(c, v instanceof String str ? renamed(str, hex) : v));
                copy.add(row);
            }
            out.put(t, copy);
        });
        return out;
    }

    static Map<String, Object> renamedFiles(Map<String, Object> files, Map<String, String> names) {
        Map<String, Object> out = new TreeMap<>();
        files.forEach((k, v) -> out.put(renamed(k, names), v));
        return out;
    }

    /** PHP 这一步的观察结果（与 {@link #observe} 同格式）；正文没有记录时为 null（只比较表与文件）。 */
    static String phpObservation(JsonNode st) {
        if (st.get("body") == null || st.get("body").isNull()) {
            return null;
        }
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        String location = st.get("location") == null || st.get("location").isNull() ? null : st.get("location").asString();
        return observe(st.get("status").asInt(), location, body);
    }

    /**
     * 一个响应的可比较摘要：状态码、Location、JSON 正文原样，或页面（含 Ajax 拿到的 JSON 字符串页面）中取出的结构。
     * 404 与 500 只比较状态码（旧版是 ThinkPHP 的异常页）。
     */
    static String observe(int status, String location, String body) {
        StringBuilder sb = new StringBuilder("status=").append(status);
        if (location != null) {
            sb.append(" location=").append(location);
        }
        if (status == 404 || status >= 500 || location != null) {
            return sb.toString();
        }
        if (body.startsWith("{")) {
            return sb.append(" json=").append(body).toString();
        }
        if (body.startsWith("\"<")) {
            return sb.append(" ajaxpage ").append(page(JsonMapper.builder().build().readTree(body).asString())).toString();
        }
        if (body.startsWith("<")) {
            return sb.append(" page ").append(page(body)).toString();
        }
        return sb.append(" text=").append(body).toString();
    }

    private static String page(String html) {
        return String.join(" / ", AdminContentHtml.structure(html)).replaceAll("__token__ type=hidden value=[0-9a-f]+",
                "__token__ type=hidden value=TOKEN");
    }

    // ------------------------------------------------------------------ 请求

    private static final Pattern TOKEN = Pattern.compile(
            "name=\\\\?\"__token__\\\\?\"(?: type=\\\\?\"hidden\\\\?\")? value=\\\\?\"([0-9a-f]+)");

    private static String token(String body) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = TOKEN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String phpBody(JsonNode st) {
        if (st.get("body") == null || st.get("body").isNull()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
    }

    private static MockHttpServletResponse send(Variant v, String sid, JsonNode req, String phpToken, String javaToken)
            throws Exception {
        String method = req.get("method").asString();
        String path = req.get("path").asString();
        String query = req.get("query").asString();
        String uri = E + path + (query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        if (req.get("ajax").asBoolean()) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        req.get("headers").properties().forEach(e -> b.header(e.getKey(), e.getValue().asString()));
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            JsonNode mp = req.get("multipart");
            if (mp != null && !mp.isNull()) {
                Multipart m = multipart(mp);
                b.contentType(m.contentType()).content(m.body());
                // MockMvc 不解析原始 multipart 正文：同样的各部分另外放进请求（容器里由 Tomcat 解析）
                b.with(r -> {
                    for (JsonNode p : mp) {
                        org.springframework.mock.web.MockPart part = p.has("value")
                                ? new org.springframework.mock.web.MockPart(p.get("name").asString(),
                                        p.get("value").asString().getBytes(StandardCharsets.UTF_8))
                                : new org.springframework.mock.web.MockPart(p.get("name").asString(),
                                        p.get("filename").asString(), Base64.getDecoder().decode(p.get("content").asString()));
                        if (!p.has("value")) {
                            part.getHeaders().setContentType(org.springframework.http.MediaType.parseMediaType(
                                    p.get("type").asString()));
                        }
                        r.addPart(part);
                    }
                    return r;
                });
            } else {
                String post = req.get("post").asString();
                if (phpToken != null && javaToken != null) {
                    post = post.replace(phpToken, javaToken);
                }
                if (System.getProperty("golden.debug") != null) {
                    System.out.println("[golden.debug] POST " + uri + " php=" + phpToken + " java=" + javaToken + " body=" + post);
                }
                b.contentType("application/x-www-form-urlencoded").content(post.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return v.mvc().perform(b).andReturn().getResponse();
    }

    record Multipart(String contentType, byte[] body) {
    }

    /** 与黄金脚本 multipart() 相同的正文。 */
    static Multipart multipart(JsonNode parts) {
        String boundary = "----goldenboundary7d3a";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (JsonNode p : parts) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (p.has("value")) {
                out.writeBytes(("Content-Disposition: form-data; name=\"" + p.get("name").asString() + "\"\r\n\r\n"
                        + p.get("value").asString() + "\r\n").getBytes(StandardCharsets.UTF_8));
            } else {
                out.writeBytes(("Content-Disposition: form-data; name=\"" + p.get("name").asString() + "\"; filename=\""
                        + p.get("filename").asString() + "\"\r\nContent-Type: " + p.get("type").asString() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.writeBytes(Base64.getDecoder().decode(p.get("content").asString()));
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
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

    // ------------------------------------------------------------------ ② 并发编辑（22 文档第 9 节）

    /** PHP 8.0 黄金里的一个场景（取它的旧配置、种子行与文件）。 */
    private static JsonNode scenario(String label) {
        for (JsonNode s : load(PHP80).get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException(label);
    }

    /** 按场景准备库与 upload/，返回它的应用上下文。 */
    private static Variant prepare(String label) throws Exception {
        JsonNode s = scenario(label);
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        resetFiles(v.siteRoot(), s.get("filesSeed"));
        return v;
    }

    /** 场景里某一步 POST 的正文解析成的参数表（与控制器 {@code input('post.')} 相同的解析）。 */
    private static PhpForm postOf(String label, String step) {
        for (JsonNode st : scenario(label).get("steps")) {
            if (st.has("request") && st.get("label").asString().equals(step)) {
                return form(st.get("request").get("post").asString());
            }
        }
        throw new IllegalArgumentException(label + "/" + step);
    }

    private static PhpForm form(String urlencoded) {
        return AdminContentActions.form(AdminParams.parse(urlencoded.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static Map<String, String> vodRow(long id) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT vod_name, vod_blurb, vod_remarks, vod_hits FROM mac_vod WHERE vod_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("vod " + id).isTrue();
                return Map.of("name", rs.getString(1), "blurb", rs.getString(2), "remarks", rs.getString(3),
                        "hits", rs.getString(4));
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 每个名称 → 该名称最小的编号。 */
    private static Map<String, Long> smallestIdByName() throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT vod_name, MIN(vod_id) FROM mac_vod GROUP BY vod_name")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /**
     * 12 个管理员同时保存同一条视频的编辑表单（各自不同的名称、简介、备注、点击数）：全部"保存成功"，库里是其中某一份表单的整行，
     * 不会出现两份表单的列混在一行里（整行覆盖是一条 UPDATE）。
     */
    @Test
    @Order(2)
    void concurrentEditsOfOneVideoLeaveExactlyOneWholeForm() throws Exception {
        Variant v = prepare("vod-save");
        ContentAdminServices content = v.app().getBean(ContentAdminServices.class);
        long rows = count("SELECT COUNT(*) FROM mac_vod");
        var outcome = ConcurrentWrites.run(12, i -> content.vods().save(postOf("vod-save", "edit")
                .put("vod_name", "并发改名" + i).put("vod_blurb", "简介" + i).put("vod_remarks", "备注" + i)
                .put("vod_hits", Integer.toString(100 + i))).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(12);
        Map<String, String> row = vodRow(1);
        assertThat(row.get("name")).startsWith("并发改名");
        String k = row.get("name").substring("并发改名".length());
        assertThat(row).containsEntry("blurb", "简介" + k).containsEntry("remarks", "备注" + k)
                .containsEntry("hits", Integer.toString(100 + Integer.parseInt(k)));
        assertThat(count("SELECT COUNT(*) FROM mac_vod")).as("编辑不新增行").isEqualTo(rows);
    }

    /**
     * 后台视频表单带计数字段时整行覆盖（路线图 B13 验收、03 第 3.1 节，与旧版一致）：打开表单之后前台的点击（B11 原子 +1）被保存时
     * 表单里的点击数覆盖；并发时最终点击数是表单值加上保存之后才执行的点击，保存与点击都不出错。
     */
    @Test
    @Order(3)
    void adminSaveOverwritesCountersLikeLegacyEvenWhileVisitorsClick() throws Exception {
        Variant v = prepare("vod-save");
        ContentAdminServices content = v.app().getBean(ContentAdminServices.class);
        ContentCounterService counters = v.app().getBean(ContentCounterService.class);
        PhpForm opened = postOf("vod-save", "edit");
        assertThat(opened.text("vod_hits")).isEqualTo("55");
        for (int i = 0; i < 5; i++) {
            counters.hit(CounterTarget.VOD, 1);
        }
        assertThat(content.vods().save(opened.copy()).ok()).isTrue();
        assertThat(vodRow(1)).as("打开表单后的 5 次点击被表单值覆盖").containsEntry("hits", "55");

        var outcome = ConcurrentWrites.run(16, i -> i == 0
                ? (content.vods().save(opened.copy()).ok() ? "saved" : "failed")
                : counters.hit(CounterTarget.VOD, 1).status().name());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).contains("saved").doesNotContain("failed", "REJECTED");
        assertThat(Long.parseLong(vodRow(1).get("hits"))).isBetween(55L, 70L);
    }

    /**
     * 查重（不建临时表，D-B13-7）：8 个并发新增同名视频全部保存（旧版也没有唯一约束），查重列表列出这一组；6 个管理员同时"查重删除"
     * （保留最小编号）全部"删除成功"，每个名称只剩编号最小的一条。
     */
    @Test
    @Order(4)
    void concurrentSameNameAddsAreListedAndConcurrentRepeatDeletesKeepTheSmallestId() throws Exception {
        Variant v = prepare("vod-save");
        ContentAdminServices content = v.app().getBean(ContentAdminServices.class);
        var added = ConcurrentWrites.run(8, i -> content.vods().save(postOf("vod-save", "new")
                .put("vod_name", "重名甲")).ok());
        assertThat(added.failures()).isEmpty();
        assertThat(added.count(true)).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM mac_vod WHERE vod_name = ?", "重名甲")).isEqualTo(8);
        AdminRowPage page = content.vods().list(form("repeat=1"), new AdminPaging(1, 100));
        assertThat(page.rows().stream().filter(r -> "重名甲".equals(r.s("vod_name"))).count()).isEqualTo(8);

        Map<String, Long> keep = smallestIdByName();
        var deleted = ConcurrentWrites.run(6, i -> content.vods().delete(form("repeat=1")).ok());
        assertThat(deleted.failures()).isEmpty();
        assertThat(deleted.count(true)).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM mac_vod")).isEqualTo(keep.size());
        assertThat(smallestIdByName()).isEqualTo(keep);
    }

    /**
     * 16 个请求同时登记同一个附件文件：旧版"先查后插"会登记多行，新版条件插入只登记一行、只有一个请求报告"新登记"；不同的文件互不影响。
     */
    @Test
    @Order(5)
    void concurrentRegistrationOfOneFileInsertsOneAnnexRow() throws Exception {
        Variant v = prepare("annex-list");
        AnnexAdminService annexes = v.app().getBean(ContentAdminServices.class).annexes();
        long before = count("SELECT COUNT(*) FROM mac_annex");
        String file = "upload/vod/20261025-1/same.jpg";
        var outcome = ConcurrentWrites.run(16, i -> annexes.register(file, "image", "12.5"));
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM mac_annex WHERE annex_file = ?", file)).isEqualTo(1);
        assertThat(count("SELECT annex_size FROM mac_annex WHERE annex_file = ?", file)).as("按 MySQL 整数列取整")
                .isEqualTo(13);
        var distinct = ConcurrentWrites.run(16, i -> annexes.register("upload/vod/20261025-1/f" + i + ".jpg", "image", "1"));
        assertThat(distinct.failures()).isEmpty();
        assertThat(distinct.count(true)).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM mac_annex")).isEqualTo(before + 17);
    }

    /** 同一批编号被多个管理员同时删除（友链、评论、留言各 8 个）：全部"删除成功"，只删这些行，其余行不受影响。 */
    @Test
    @Order(6)
    void concurrentDeletesOfTheSameIdsAllSucceedAndTouchNothingElse() throws Exception {
        Variant v = prepare("vod-save");
        ContentAdminServices content = v.app().getBean(ContentAdminServices.class);
        CommentAdminService comments = v.app().getBean(CommentAdminService.class);
        long links = count("SELECT COUNT(*) FROM mac_link");
        long cms = count("SELECT COUNT(*) FROM mac_comment");
        long gbooks = count("SELECT COUNT(*) FROM mac_gbook");
        assertThat(count("SELECT COUNT(*) FROM mac_comment WHERE comment_id IN (1, 2)")).isEqualTo(2);
        var outcome = ConcurrentWrites.run(24, i -> switch (i % 3) {
            case 0 -> content.links().delete(form("ids=1")).ok();
            case 1 -> comments.deleteComments(form("ids=1,2")).ok();
            default -> comments.deleteGbooks(form("ids=1")).ok();
        });
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(24);
        assertThat(count("SELECT COUNT(*) FROM mac_link WHERE link_id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_link")).isEqualTo(links - 1);
        assertThat(count("SELECT COUNT(*) FROM mac_comment WHERE comment_id IN (1, 2)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_comment")).isEqualTo(cms - 2);
        assertThat(count("SELECT COUNT(*) FROM mac_gbook WHERE gbook_id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_gbook")).isEqualTo(gbooks - 1);
    }

    // ------------------------------------------------------------------ ③ 回滚演练

    /**
     * 快照（跟踪的 12 张表）→ 经各服务做一遍本批的写入（友链增改删、分类字段、视频新增/编辑/字段/删除、文章编辑、评论与留言的修改和删除、
     * 附件登记）→ {@code DmlRestore} 只用 DML 恢复 → 与快照逐行 diff 为空 → 表结构指纹与导入 install.sql 时相同。
     * upload/ 下被删除的图片不在 DML 恢复范围内（22 文档第 11 节：回滚前另行备份 upload/）。
     */
    @Test
    @Order(7)
    void rollbackDrillRestoresEveryB13WriteWithDmlOnly() throws Exception {
        Variant v = prepare("vod-save");
        ContentAdminServices content = v.app().getBean(ContentAdminServices.class);
        CommentAdminService comments = v.app().getBean(CommentAdminService.class);
        List<String> tables = List.copyOf(TABLES.keySet());
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        assertThat(content.links().save(postOf("link-save", "new")).ok()).isTrue();
        assertThat(content.links().save(postOf("link-save", "edit")).ok()).isTrue();
        assertThat(content.links().delete(form("ids=2")).ok()).isTrue();
        assertThat(content.types().field(form("ids=2&col=type_status&val=0")).ok()).isTrue();
        assertThat(content.vods().save(postOf("vod-save", "new")).ok()).isTrue();
        assertThat(content.vods().save(postOf("vod-save", "edit")).ok()).isTrue();
        assertThat(content.vods().field(form("ids=2,3&col=vod_level&val=9")).ok()).isTrue();
        assertThat(content.vods().delete(form("ids=4")).ok()).isTrue();
        assertThat(content.arts().save(postOf("art-save", "edit")).ok()).isTrue();
        assertThat(comments.saveComment(postOf("comment-edit", "save")).ok()).isTrue();
        assertThat(comments.saveGbook(postOf("gbook", "reply-save")).ok()).isTrue();
        assertThat(comments.deleteComments(form("ids=3")).ok()).isTrue();
        assertThat(comments.deleteGbooks(form("ids=2")).ok()).isTrue();
        assertThat(content.annexes().register("upload/vod/20261025-1/drill.jpg", "image", "3")).isTrue();
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
            System.out.println("[mysql-it] B13 rollback drill restored with " + plan.size() + " DML statements");
        }
    }

    // ------------------------------------------------------------------ 表与文件

    /** 清空跟踪的表、复位自增计数（一次性库上的测试夹具，与黄金脚本相同），写入场景的行与两位管理员。 */
    private static void seed(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String t : TABLES.keySet()) {
                    st.execute("DELETE FROM " + t);
                    st.execute("ALTER TABLE " + t + " AUTO_INCREMENT = 1");
                }
                st.execute("DELETE FROM mac_admin");
                st.execute("ALTER TABLE mac_admin AUTO_INCREMENT = 1");
                st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                        + "(1, 'admin', '" + md5("admin888") + "', 1, ''), "
                        + "(2, 'editor', '" + md5("ed pass") + "', 1, ',vod/data,vod/info,link/index,link/info,')");
            }
            Iterator<Map.Entry<String, JsonNode>> it = seed.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String table = e.getKey();
                for (JsonNode row : e.getValue()) {
                    List<String> cols = new ArrayList<>();
                    row.properties().forEach(x -> cols.add(x.getKey()));
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < cols.size(); i++) {
                            String kind = columnKinds.get(table).get(cols.get(i));
                            JsonNode val = row.get(cols.get(i));
                            if ("text".equals(kind)) {
                                ps.setString(i + 1, new String(HexFormat.of().parseHex(val.asString()), StandardCharsets.UTF_8));
                            } else {
                                ps.setString(i + 1, val.asString());
                            }
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    /** 新版这边跟踪的各表的全部行（与黄金脚本 read_all 同样的编码：整数为整数，decimal 为字符串，其余十六进制）。 */
    static Map<String, List<Map<String, Object>>> javaRows() throws SQLException {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> t : TABLES.entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + t.getKey() + " ORDER BY " + t.getValue())) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnLabel(i);
                            String kind = columnKinds.get(t.getKey()).get(col);
                            if ("int".equals(kind)) {
                                row.put(col, rs.getLong(i));
                            } else if ("decimal".equals(kind)) {
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

    /** 黄金记录的行（JSON → 与 {@link #javaRows} 同样的编码）。 */
    static Map<String, List<Map<String, Object>>> rows(JsonNode tables) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String t : TABLES.keySet()) {
            out.put(t, tableRows(tables.get(t)));
        }
        return out;
    }

    private static List<Map<String, Object>> tableRows(JsonNode rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (JsonNode r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            r.properties().forEach(e -> row.put(e.getKey(), e.getValue().isNumber() ? (Object) e.getValue().asLong()
                    : e.getValue().asString()));
            out.add(row);
        }
        return out;
    }

    private static Map<String, List<Map<String, Object>>> merge(Map<String, List<Map<String, Object>>> last, JsonNode changed) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>(last);
        if (changed != null) {
            changed.properties().forEach(e -> out.put(e.getKey(), tableRows(e.getValue())));
        }
        return out;
    }

    /**
     * 逐行逐列比较；写入的时间列：PHP 的值在 [started, after] 内且新版的值等于 started 时视为相同，并记下这一对取值
     * （{@code accepted}），同一场景后面的步骤里同一对取值同样视为相同（行在之前的步骤写入）。
     */
    static List<String> diffRows(List<Map<String, Object>> php, List<Map<String, Object>> java, long started, long after,
                                 java.util.Set<String> accepted) {
        List<String> out = new ArrayList<>();
        if (php.size() != java.size()) {
            out.add("行数 php=" + php.size() + " java=" + java.size());
        }
        for (int i = 0; i < Math.min(php.size(), java.size()); i++) {
            Map<String, Object> p = php.get(i);
            Map<String, Object> j = java.get(i);
            for (Map.Entry<String, Object> e : p.entrySet()) {
                Object jv = j.get(e.getKey());
                if (e.getValue().equals(jv)) {
                    continue;
                }
                if (e.getValue() instanceof Long pv && jv instanceof Long javaValue) {
                    if (pv >= started && pv <= after && javaValue == started) {
                        accepted.add(pv + "=" + javaValue);
                        continue;
                    }
                    if (accepted.contains(pv + "=" + javaValue)) {
                        continue;
                    }
                }
                out.add("行 " + i + " 列 " + e.getKey() + " php=" + show(e.getValue()) + " java=" + show(jv));
            }
        }
        if (php.size() != java.size()) {
            out.add("php: " + php);
            out.add("java: " + java);
        }
        return out;
    }

    private static String show(Object v) {
        if (v instanceof String s && s.matches("([0-9a-f]{2})+") && s.length() > 1) {
            try {
                return s + "(" + new String(HexFormat.of().parseHex(s), StandardCharsets.UTF_8) + ")";
            } catch (IllegalArgumentException e) {
                return s;
            }
        }
        return String.valueOf(v);
    }

    private static void resetFiles(Path site, JsonNode seed) throws IOException {
        Path upload = site.resolve("upload");
        deleteTree(upload);
        Files.createDirectories(upload);
        if (seed != null) {
            Iterator<Map.Entry<String, JsonNode>> it = seed.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Path f = site.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, Base64.getDecoder().decode(e.getValue().asString()));
                // 与黄金脚本相同的固定修改时间（附件目录浏览显示修改时间）
                Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
            }
        }
    }

    private static Map<String, Object> files(JsonNode node) {
        Map<String, Object> out = new TreeMap<>();
        if (node != null && !node.isNull()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().get("size").asLong() + ":"
                    + e.getValue().get("sha256").asString()));
        }
        return out;
    }

    private static Map<String, Object> listFiles(Path site) throws IOException {
        Map<String, Object> out = new TreeMap<>();
        Path upload = site.resolve("upload");
        if (!Files.isDirectory(upload)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(upload)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = site.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.size(p) + ":" + sha256(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    static JsonNode load(String resource) {
        try (InputStream in = AdminContentGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            return JsonMapper.builder().build().readTree(in);
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
                String kind = List.of("tinyint", "smallint", "mediumint", "int", "bigint").contains(type) ? "int"
                        : "decimal".equals(type) ? "decimal" : "text";
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), kind);
            }
        }
        return out;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
