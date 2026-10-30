package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B28 百度推送（E09）与友链回链检测（E11）的真实 MySQL 对照（./mvnw verify -Pmysql-it；30 文档第 8 节）。
 *
 * <p>外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 写入与黄金脚本相同的种子（{@code {T}} 换成本机今天零点）→
 * 启动本机模拟服务（{@link LoopbackMockServer}，扮演百度接口与友链页面）→ 按场景的配置启动应用（后台、定时任务打开；百度接口地址指向模拟服务）
 * → 复放 PHP 7.3 与 8.0 的黄金（{@code urlsend_backlink_golden.php}）：每一步比较状态码与正文（推送输出逐字节、回链检测的 JSON 逐字节、
 * 跳转提示页比较结构），以及这一步模拟服务收到的请求（方法、地址的路径与查询串、Content-Type、User-Agent、请求体）。有意差异见 30 文档第 9 节，
 * 按 {@link #expected} 换算后断言。结束时表结构指纹不变（本批只读），删库并确认不存在。
 */
class UrlsendBacklinkGoldenMySqlIT {

    static final String PHP80 = "integration/b28/php80-golden.json";
    static final String PHP73 = "integration/b28/php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 旧版在 PHP 8.0 下 mac_curl_post 的致命错误（D-B28-1）。 */
    private static final String PHP8_CURL_FATAL = "is not a valid cURL option";
    private static final String PHP8_IMPLODE_FATAL = "implode(): Argument #1 ($pieces) must be of type array";
    private static final String NAME_RAW = "视频<b>二</b>";
    private static final String NAME_ESCAPED = "视频&lt;b&gt;二&lt;/b&gt;";

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static LoopbackMockServer mock;
    private static final ConcurrentLinkedQueue<MockResponse> BAIDU = new ConcurrentLinkedQueue<>();
    private static Path work;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        long today = LocalDate.now(PRC).atStartOfDay(PRC).toEpochSecond();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            st.execute("SET SESSION sql_mode = ''");
            for (JsonNode sql : load(PHP73).get("seed")) {
                st.execute(sql.asString().replace("{T}", Long.toString(today)));
            }
        }
        mock = LoopbackMockServer.start();
        mock.stub("POST", "/urls", req -> {
            MockResponse r = BAIDU.poll();
            return r == null ? MockResponse.of(599, "application/json", "unplanned") : r;
        });
        work = Files.createTempDirectory("maccms-b28-it");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (mock != null) {
            mock.close();
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

    // ------------------------------------------------------------------ 黄金复放

    @Test
    void scenariosMatchPhpGolden() throws Exception {
        JsonNode g73 = load(PHP73);
        JsonNode g80 = load(PHP80);
        Map<String, JsonNode> steps73 = new LinkedHashMap<>();
        for (JsonNode s : g73.get("scenarios")) {
            for (JsonNode st : s.get("steps")) {
                steps73.put(s.get("label").asString() + "/" + st.get("label").asString(), st);
            }
        }
        List<String> problems = new ArrayList<>();
        int compared = 0;
        int php8Fatal = 0;
        // 每个场景按自己的配置起一个应用（第三方配置启动时读一次，D-B23-5）；两版的同名场景配置相同，共用一个应用
        for (int i = 0; i < g73.get("scenarios").size(); i++) {
            JsonNode s73 = g73.get("scenarios").get(i);
            JsonNode s80 = g80.get("scenarios").get(i);
            assertThat(s80.get("label").asString()).isEqualTo(s73.get("label").asString());
            ConfigurableApplicationContext app = start(g73.get("baseConfig"), s73.get("config"));
            try {
                MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
                for (JsonNode s : List.of(s73, s80)) {
                    String tag = s == s73 ? "73" : "80";
                    prepare(s);
                    String sid = null;
                    for (JsonNode st : s.get("steps")) {
                        String key = s.get("label").asString() + "/" + st.get("label").asString();
                        JsonNode req = st.get("request");
                        int before = mock.requests().size();
                        MockHttpServletResponse r = send(mvc, sid, req);
                        sid = cookie(r, sid);
                        if (st.get("label").asString().equals("login")) {
                            continue;
                        }
                        String phpBody = decode(st.get("body"));
                        JsonNode ref = st;
                        if (tag.equals("80") && (phpBody.contains(PHP8_CURL_FATAL) || phpBody.contains(PHP8_IMPLODE_FATAL)
                                || key.startsWith("backlink-empty-wapurl/"))) {
                            // D-B28-1、D-B28-2：PHP 8.0 下旧版推送根本发不出去（curl 选项、取不到数据时 implode 的类型错误）、
                            // 空手机域名判为"正常"，新版以 PHP 7.3 为准
                            php8Fatal += phpBody.contains(PHP8_CURL_FATAL) ? 1 : 0;
                            ref = steps73.get(key);
                        }
                        String want = expected(key, ref);
                        String got = observe(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
                        if (!want.equals(got)) {
                            problems.add(tag + ":" + key + "\n  expected: " + want + "\n  actual:   " + got);
                        }
                        String wantMock = expectedMock(key, ref);
                        List<RecordedRequest> all = mock.requests();
                        String gotMock = javaMock(all.subList(before, all.size()));
                        if (!wantMock.equals(gotMock)) {
                            problems.add(tag + ":" + key + " 模拟服务收到的请求\n  expected: " + wantMock + "\n  actual:   " + gotMock);
                        }
                        compared++;
                    }
                    if (s.get("timmingAfter") != null && !s.get("timmingAfter").isNull()) {
                        PhpArray t = VarExportParser.parse(Files.readAllBytes(work.resolve("application/extra/timming.php")));
                        long runtime = Long.parseLong(com.maccms.backend.legacyconfig.application.view.PhpValues.text(
                                t.path("push", "runtime")));
                        if ((runtime > 0) != s.get("timmingAfter").get("runtimeWritten").asBoolean()) {
                            problems.add(tag + ":" + s.get("label").asString() + " timming.php 的 runtime 回写与 PHP 不同");
                        }
                    }
                }
            } finally {
                app.close();
            }
        }
        System.out.println("[mysql-it] b28 golden steps compared: " + compared + " (php 8.0 curl fatal steps replaced by 7.3: "
                + php8Fatal + ")");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "b28-golden-problems.txt"), String.join("\n\n", problems), StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已换算），详见 target/b28-golden-problems.txt").isEmpty();
        assertThat(compared).isGreaterThan(80);
        assertThat(php8Fatal).as("PHP 8.0 黄金里旧版推送的致命错误").isGreaterThan(10);
    }

    /**
     * 新版应有的结果：PHP 的结果按 30 文档第 9 节的有意差异换算。
     * <ul>
     * <li>D-B28-4：名称按 HTML 转义；</li>
     * <li>D-B28-3：取不到数据时不再把空列表推给百度（旧版随后的"请求失败"一行没有）；</li>
     * <li>D-B28-7：直接访问 urlsend/data 时旧版在列表后面报"variable type error"，新版只有列表；</li>
     * <li>D-B23-4：file:// 地址不抓取，按抓取失败；</li>
     * <li>旧 {@code mac_jump} 的内联脚本改为 progress.js（同 B16），比较跳转地址。</li>
     * </ul>
     */
    static String expected(String key, JsonNode st) {
        String body = decode(st.get("body")).replace(NAME_RAW, NAME_ESCAPED);
        if (key.equals("push-edge/no-data") || key.equals("push-edge/bad-mid")) {
            body = body.replace("没有获取到数据<br>请求失败，请重试<br>", "没有获取到数据<br>");
        }
        if (key.equals("push-edge/data")) {
            body = body.substring(0, body.indexOf("<!DOCTYPE"));
        }
        if (key.equals("backlink/file-scheme")) {
            body = "{\"code\":101,\"msg\":\"[www.example.test]反向链接异常，[m.example.test]反向链接异常\"}";
        }
        return observe(st.get("status").asInt(), body);
    }

    static String expectedMock(String key, JsonNode st) {
        if (key.equals("push-edge/no-data") || key.equals("push-edge/bad-mid")) {
            return "[]";
        }
        List<String> out = new ArrayList<>();
        for (JsonNode m : st.get("mock")) {
            String uri = m.get("uri").asString();
            if (uri.startsWith("http://data.zz.baidu.com")) {
                uri = uri.substring("http://data.zz.baidu.com".length());
            }
            out.add(m.get("method").asString() + " " + uri + " ct=" + m.get("contentType").asString() + " ua="
                    + m.get("userAgent").asString() + " body=" + decode(m.get("body")));
        }
        return out.toString();
    }

    static String javaMock(List<RecordedRequest> requests) {
        List<String> out = new ArrayList<>();
        for (RecordedRequest q : requests) {
            String ct = q.header("content-type");
            out.add(q.method() + " " + q.path() + (q.query().isEmpty() ? "" : "?" + q.query()) + " ct=" + (ct == null ? "" : ct)
                    + " ua=" + q.header("user-agent") + " body=" + q.bodyText());
        }
        return out.toString();
    }

    private static final Pattern PHP_JUMP = Pattern.compile(
            "<script>setTimeout\\(function \\(\\)\\{location\\.href=\"([^\"]*)\";\\},3000\\);</script><span>[^<]*</span><a href=\"[^\"]*\" >[^<]*</a><br>");
    private static final Pattern JAVA_JUMP = Pattern.compile(
            "<script src=\"[^\"]*/admin-assets/progress\\.js\" data-url=\"([^\"]*)\" data-seconds=\"3\"></script><span>[^<]*</span><a href=\"[^\"]*\" >[^<]*</a><br>");

    /** 状态码 + 正文：跳转提示页比较结构（AdminContentGoldenMySqlIT 的规则），推送输出把跳转换成"[jump 地址]"后逐字节比较。 */
    static String observe(int status, String body) {
        if (body.startsWith("<!DOCTYPE html PUBLIC") || body.startsWith("<!DOCTYPE html>")) {
            return AdminContentGoldenMySqlIT.observe(status, null, body);
        }
        Matcher m = PHP_JUMP.matcher(body);
        body = m.replaceAll(r -> Matcher.quoteReplacement("[jump " + r.group(1) + "]"));
        m = JAVA_JUMP.matcher(body);
        body = m.replaceAll(r -> Matcher.quoteReplacement("[jump " + r.group(1).replace("&amp;", "&") + "]"));
        return "status=" + status + " " + body;
    }

    // ------------------------------------------------------------------ 场景准备

    /** 本场景的模拟服务（百度队列、友链页面）与 timming.php。 */
    private static void prepare(JsonNode s) throws IOException {
        BAIDU.clear();
        for (JsonNode b : s.get("baidu")) {
            BAIDU.add(MockResponse.of(b.get("status").asInt(), "application/json", b.get("body").asString()));
        }
        for (Map.Entry<String, JsonNode> p : s.get("pages").properties()) {
            JsonNode v = p.getValue();
            MockResponse r = v.has("location") ? MockResponse.redirect(v.get("status").asInt(), v.get("location").asString())
                    : MockResponse.of(v.get("status").asInt(), "text/html; charset=utf-8", v.get("body").asString());
            mock.stub("GET", p.getKey(), r);
        }
        Path timming = work.resolve("application/extra/timming.php");
        if (s.get("timming") != null && !s.get("timming").isNull()) {
            Files.write(timming, VarExportWriter.macArr2File(php(s.get("timming"))));
        }
    }

    /** 按场景配置写旧配置目录（仓库 extra/*.php，maccms.php 并入黄金的基础配置与场景配置）并启动应用。 */
    private static ConfigurableApplicationContext start(JsonNode base, JsonNode config) throws IOException {
        Path extra = work.resolve("application/extra");
        if (Files.exists(work.resolve("application"))) {
            deleteTree(work.resolve("application"));
        }
        Files.createDirectories(extra);
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.writeString(extra.resolve(f.getFileName().toString()),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
        }
        PhpArray maccms = VarExportParser.parse(Files.readAllBytes(extra.resolve("maccms.php")));
        maccms = merge(maccms, php(base));
        if (config != null && !config.isNull()) {
            maccms = merge(maccms, php(config));
        }
        Files.write(extra.resolve("maccms.php"), VarExportWriter.macArr2File(maccms));
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://admin.example.test/", "--maccms.legacy-config.dir=" + work.resolve("application"),
                "--maccms.timming.enabled=true", "--maccms.upload.dir=" + work.resolve("upload"),
                "--maccms.integration.urlsend.baidu-endpoint=" + mock.uri("/urls"),
                "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            return new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
    }

    /** JSON 对象 → PHP 数组（字符串、整数照原样）。 */
    static PhpArray php(JsonNode n) {
        Map<Object, PhpValue> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            JsonNode v = e.getValue();
            PhpValue pv = v.isObject() ? php(v) : v.isIntegralNumber() ? new PhpValue.PhpInt(v.asLong())
                    : new PhpValue.PhpString(v.asString());
            m.put(PhpArray.normalizeKey(e.getKey()), pv);
        }
        return new PhpArray(m);
    }

    /** 递归合并（后者覆盖前者），同黄金脚本的 deep_merge。 */
    static PhpArray merge(PhpArray base, PhpArray over) {
        Map<Object, PhpValue> m = new LinkedHashMap<>(base.entries());
        for (Map.Entry<Object, PhpValue> e : over.entries().entrySet()) {
            PhpValue old = m.get(e.getKey());
            m.put(e.getKey(), old instanceof PhpArray a && e.getValue() instanceof PhpArray b ? merge(a, b) : e.getValue());
        }
        return new PhpArray(m);
    }

    // ------------------------------------------------------------------ 请求

    private static MockHttpServletResponse send(MockMvc mvc, String sid, JsonNode req) throws Exception {
        // {local}：黄金脚本里是本机临时文件的 file:// 地址；新版不抓 file://（D-B28-10），这里换成本次的临时目录下的文件
        String query = req.get("query").asString().replace("%7Bmock%7D", java.net.URLEncoder.encode(
                "http://127.0.0.1:" + mock.port(), StandardCharsets.UTF_8)).replace("%7Blocal%7D", java.net.URLEncoder.encode(
                work.resolve("local-page.html").toUri().toString(), StandardCharsets.UTF_8));
        boolean api = req.get("entry").asString().equals("api");
        String uri = (api ? "/api.php" : E) + req.get("path").asString() + (query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(req.get("method").asString()),
                java.net.URI.create(uri));
        if (req.get("ajax").asBoolean()) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        b.header("User-Agent", "Mozilla/5.0 (golden)");
        if (sid != null && !api) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(req.get("method").asString())) {
            b.contentType("application/x-www-form-urlencoded").content(req.get("post").asString().getBytes(StandardCharsets.ISO_8859_1));
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

    // ------------------------------------------------------------------ 工具

    static String decode(JsonNode b64) {
        return new String(Base64.getDecoder().decode(b64.asString()), StandardCharsets.UTF_8);
    }

    static JsonNode load(String resource) {
        try (InputStream in = UrlsendBacklinkGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> installStatements() throws IOException {
        StringBuilder kept = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                kept.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : kept.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.strip());
            }
        }
        return out;
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(db == null ? adminUrl : jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static String jdbcUrlFor(String db) {
        int q = adminUrl.indexOf('?');
        String base = q < 0 ? adminUrl : adminUrl.substring(0, q);
        String params = q < 0 ? "" : adminUrl.substring(q);
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + db + params;
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
