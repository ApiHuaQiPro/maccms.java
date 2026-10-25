package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.admin.AdminGolden;
import com.maccms.backend.admin.AdminHtml;
import com.maccms.backend.admin.application.AdminAccountService;
import com.maccms.backend.admin.application.AdminLoginService;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台的真实 MySQL 对照（B12，./mvnw verify -Pmysql-it；21 文档第 9 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 取表结构指纹与快照 S0 → 按场景的旧配置
 * （登录验证码、语言）各启动一个打开了可写数据源与后台的应用上下文 →
 * ① 逐个复放 PHP 8.0 与 7.3 的后台黄金（admin_golden.php）：每个场景先把 mac_admin 重置为场景的行、换新的 cookie，按同样的请求
 * （方法、路径、查询串、正文字节、Ajax 头、Referer）发给新版，比较状态码、Location、JSON 字节、页面里取出的结构（AdminHtml）、
 * is_iframe cookie，以及请求后 mac_admin 的全部行；有意差异（21 文档第 11 节）按 {@link #JAVA} 断言新版的结果；
 * ② 并发：同一管理员 16 线程同时登录（登录次数原子 +1，一次不丢）；登录与改密竞争（改密之后用旧密码的登录全部失败，登录记录只写给通过的那几次）；
 * ③ 回滚演练：快照 → 本批全部写操作 → 只用 DML 恢复 → 与快照逐行 diff 为空 → 表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final List<String> TABLES = List.of("mac_admin", "mac_vod_search");
    private static final String E = "/_admin.php";
    private static final String ADMIN_COLUMNS = "admin_id, admin_name, admin_pwd, admin_random, admin_status, admin_auth, "
            + "admin_login_time, admin_login_ip, admin_login_num, admin_last_login_time, admin_last_login_ip";

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static DatabaseSnapshot s0;
    private static Map<String, String> schemaAtImport;
    private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();
    private static final List<Path> TEMP_DIRS = new ArrayList<>();
    private static long quickmenuClock = Instant.now().getEpochSecond();
    private static final long RUN_STARTED = Instant.now().getEpochSecond();

    /** 一组旧配置对应的应用上下文。 */
    record Variant(ConfigurableApplicationContext app, MockMvc mvc, Path legacyDir, AdminSessions sessions,
                   AdminSettings settings) {
    }

    // ------------------------------------------------------------------ 有意差异（21 文档第 11 节）：场景/步骤 → 新版的观察结果

    /**
     * 键为 {@code 场景/步骤}（两版 PHP 都适用）或 {@code 80:场景/步骤}、{@code 73:场景/步骤}（只对该版）。值为新版的观察结果（与
     * {@link #observe} 的格式相同），按值逐字比较。
     */
    static final Map<String, String> JAVA = new HashMap<>();
    /** 新版请求后 mac_admin 与 PHP 不同的步骤：值为新版应有的行（相对 PHP 行的调整说明，由 {@link #expectedRows} 解释）。 */
    static final Map<String, String> JAVA_ROWS = new HashMap<>();

    static {
        String denied = "status=200 json={\"code\":0,\"msg\":\"您没有权限访问此页面\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        String toLogin = "status=302 location=/_admin.php/admin/index/login.html";
        String paramErr = "status=200 json={\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        // D2 ⑤：旧版用登录时的会话快照，新版每个请求按当前行判定
        JAVA.put("legacy-snapshot/after-revoke", denied);
        JAVA.put("legacy-snapshot/after-grant", "AJAXPAGE");
        JAVA.put("legacy-snapshot/after-password", toLogin);
        JAVA.put("legacy-snapshot/unlock-old", toLogin);
        JAVA.put("legacy-snapshot/unlock-new", toLogin);
        JAVA.put("legacy-snapshot/after-disable", toLogin);
        JAVA.put("legacy-snapshot/after-delete", toLogin);
        JAVA.put("admin-field/after-self-off", toLogin);
        // D-B12-6：退出不需要权限
        JAVA.put("auth-viewer/html index/logout", toLogin);
        JAVA.put("auth-editor/index/logout", toLogin);
        JAVA.put("auth-editor/after-logout", toLogin);
        // D-B08-4：验证码猜错即作废
        JAVA.put("captcha/right-after-wrong", "status=200 json={\"code\":0,\"msg\":\"验证码错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        JAVA.put("captcha/welcome", toLogin);
        JAVA_ROWS.put("captcha/right-after-wrong", "previous");
        // 后续批次的动作：B12 只给出提示（旧版此处是视频列表页，B13 实现）
        JAVA.put("auth-editor/vod/data", "status=501");
        // 旧模板把账号原样写进 value="…"（不转义），含双引号时属性被截断；新版转义
        JAVA.put("admin-info-view/quote", "REPLACE:name=q pwd= => name=q\"uote pwd=");
        // D-B12-8：数组形式的账号/密码按缺失处理（旧版 PHP 8 TypeError 500；7.3 账号数组 500、密码数组按"账号或密码错误"）
        JAVA.put("login-array-fields/pwd-array", paramErr);
        JAVA.put("login-array-fields/name-array", paramErr);
        // D-B12-9：编辑时不交 admin_auth[] 按只有 index/welcome 处理（同 PHP 7.3；PHP 8 TypeError 500）
        JAVA.put("80:admin-save-keep-pwd/edit-keep-pwd",
                "status=200 json={\"code\":1,\"msg\":\"保存成功!\",\"data\":\"\",\"url\":\"\\/_admin.php\\/admin\\/admin\\/info.html\",\"wait\":3}");
        JAVA_ROWS.put("80:admin-save-keep-pwd/edit-keep-pwd", "php73");
        JAVA.put("80:admin-save-no-auth-field/add",
                "status=200 json={\"code\":1,\"msg\":\"保存成功!\",\"data\":\"\",\"url\":\"\\/_admin.php\\/admin\\/admin\\/info.html\",\"wait\":3}");
        JAVA_ROWS.put("80:admin-save-no-auth-field/add", "php73");
        JAVA.put("80:admin-save-editor-self/edit-super",
                "status=200 json={\"code\":1,\"msg\":\"保存成功!\",\"data\":\"\",\"url\":\"\\/_admin.php\\/admin\\/admin\\/info.html\",\"wait\":3}");
        JAVA_ROWS.put("80:admin-save-editor-self/edit-super", "php73");
        // D-B12-9：只写表单的四列；状态只接受 0/1
        JAVA_ROWS.put("admin-save/extra-fields", "keep-extra-columns:3");
        JAVA.put("admin-save/status-5", paramErr);
        JAVA_ROWS.put("admin-save/status-5", "previous");
        // D-B12-10：ids 每项必须是十进制数字
        JAVA.put("admin-del/text", paramErr);
        JAVA.put("admin-del/self-loose", paramErr);
        JAVA_ROWS.put("80:admin-del/self-loose", "previous");
        // D-B12-12：同名同密码的多行只记登录的那一行
        JAVA_ROWS.put("login-duplicate-name/login", "only-first-login");
        // D-B12-11：limit 按 intval（旧版把非数字的原值写进页面脚本）
        JAVA.put("admin-list/limit-text", "LIMIT2");
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
            s0 = DatabaseSnapshot.take(c, TABLES);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (Variant v : VARIANTS.values()) {
            v.app().close();
        }
        for (Path dir : TEMP_DIRS) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).map(Path::toFile).forEach(File::delete);
            }
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
            assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
        }
    }

    /** 场景的旧配置（{@code app.admin_login_verify}、{@code app.lang}）对应的应用上下文，没有就启动一个。 */
    static Variant variant(Map<String, String> config) throws Exception {
        String verify = config.getOrDefault("admin_login_verify", "0");
        String lang = config.getOrDefault("lang", "zh-cn");
        String key = verify + "|" + lang;
        Variant v = VARIANTS.get(key);
        if (v != null) {
            return v;
        }
        Path dir = Files.createTempDirectory("maccms-b12-legacy");
        TEMP_DIRS.add(dir);
        Path extra = Files.createDirectories(dir.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("..", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        String maccms = Files.readString(extra.resolve("maccms.php"), StandardCharsets.UTF_8)
                .replace("'admin_login_verify' => '1'", "'admin_login_verify' => " + phpString(verify))
                .replace("'lang' => 'zh-cn'", "'lang' => " + phpString(lang));
        Files.writeString(extra.resolve("maccms.php"), maccms, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + dir,
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
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
        v = new Variant(app, mvc, dir, app.getBean(AdminSessions.class), app.getBean(AdminSettings.class));
        VARIANTS.put(key, v);
        return v;
    }

    private static String phpString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** 写入场景的快捷菜单（null = 仓库的 extra/quickmenu.php），并把修改时间设成递增的值（旧配置按修改时间与大小热加载）。 */
    static void quickmenu(Variant v, List<String> lines) throws Exception {
        Path f = v.legacyDir().resolve("extra").resolve("quickmenu.php");
        if (lines == null) {
            Files.copy(Path.of("..", "application", "extra", "quickmenu.php"), f,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            StringBuilder sb = new StringBuilder("<?php\nreturn array (\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append("  ").append(i).append(" => ").append(phpString(lines.get(i))).append(",\n");
            }
            sb.append(");\n");
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        }
        Files.setLastModifiedTime(f, FileTime.from(Instant.ofEpochSecond(++quickmenuClock)));
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void scenariosMatchPhpGolden() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String version : List.of(AdminGolden.PHP80, AdminGolden.PHP73)) {
            AdminGolden golden = AdminGolden.load(version);
            AdminGolden other = AdminGolden.load(version.equals(AdminGolden.PHP80) ? AdminGolden.PHP73 : AdminGolden.PHP80);
            String tag = version.equals(AdminGolden.PHP80) ? "80" : "73";
            for (AdminGolden.Scenario s : golden.scenarios()) {
                compared += replay(tag, s, other.scenario(s.label()), problems);
            }
        }
        System.out.println("[mysql-it] admin golden steps compared: " + compared);
        Files.writeString(Path.of("target", "admin-golden-problems.txt"), String.join("\n\n", problems),
                StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA/JAVA_ROWS 断言）").isEmpty();
        assertThat(compared).isGreaterThan(900);
    }

    private int replay(String tag, AdminGolden.Scenario s, AdminGolden.Scenario otherVersion, List<String> problems)
            throws Exception {
        Variant v = variant(s.config());
        quickmenu(v, s.quickmenu());
        resetAdmins(s.rows0());
        String sid = null;
        String token = null;
        List<AdminGolden.Row> previousPhp = s.rows0();
        List<AdminGolden.Row> previousJava = javaRows();
        int compared = 0;
        for (AdminGolden.Step st : s.steps()) {
            String key = s.label() + "/" + st.label();
            if (st.sql() != null) {
                try (Connection c = admin(dbName); Statement x = c.createStatement()) {
                    x.execute(st.sql());
                }
                previousPhp = st.rows();
                previousJava = javaRows();
                continue;
            }
            if (st.captcha() != null) {
                if (sid == null) {
                    sid = cookie(v.mvc().perform(MockMvcRequestBuilders.get(E + "/admin/index/verify.html"))
                            .andReturn().getResponse(), null);
                }
                captcha(v, sid, st.captcha());
                continue;
            }
            String post = st.post();
            if (token != null) {
                post = post.replaceAll("__token__=[0-9a-f]{32}", "__token__=" + token);
            }
            URI uri = URI.create(E + st.path() + (st.query().isEmpty() ? "" : "?" + st.query()));
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(st.method()), uri);
            if (st.method().equals("POST")) {
                b.contentType(MediaType.APPLICATION_FORM_URLENCODED).content(post.getBytes(StandardCharsets.ISO_8859_1));
            }
            if (st.ajax()) {
                b.header("X-Requested-With", "XMLHttpRequest");
            }
            st.headers().forEach(b::header);
            if (sid != null) {
                b.cookie(new jakarta.servlet.http.Cookie(AdminSessions.COOKIE, sid));
            }
            MockHttpServletResponse r = v.mvc().perform(b).andReturn().getResponse();
            long after = Instant.now().getEpochSecond();
            sid = cookie(r, sid);
            String javaBody = r.getContentAsString(StandardCharsets.UTF_8);
            Matcher tm = TOKEN.matcher(unwrap(javaBody));
            if (tm.find()) {
                token = tm.group(1);
            }

            String expected = JAVA.getOrDefault(tag + ":" + key, JAVA.get(key));
            String javaObs = observe(r.getStatus(), r.getHeader("Location"), javaBody, r.getHeaders("Set-Cookie"));
            if (expected == null) {
                if (st.body() == null && st.status() < 500 && st.location() == null) {
                    // 只记了状态码的步骤（PHP 的 body 没有保存）：比较状态码
                    if (r.getStatus() != st.status()) {
                        problems.add(tag + " " + key + ": status php=" + st.status() + " java=" + r.getStatus());
                    }
                } else {
                    String phpObs = observe(st.status(), st.location(), st.body() == null ? "" : st.body(), st.setCookies());
                    if (!phpObs.equals(javaObs)) {
                        problems.add(tag + " " + key + ":\n  php : " + phpObs + "\n  java: " + javaObs);
                    }
                }
            } else if (expected.equals("AJAXPAGE")) {
                if (!javaObs.startsWith("status=200 ajaxpage")) {
                    problems.add(tag + " " + key + ": expected ajax page, java: " + javaObs);
                }
            } else if (expected.startsWith("REPLACE:")) {
                String[] fromTo = expected.substring("REPLACE:".length()).split(" => ", 2);
                String phpObs = observe(st.status(), st.location(), st.body(), st.setCookies())
                        .replace(fromTo[0], fromTo[1]);
                if (!phpObs.equals(javaObs)) {
                    problems.add(tag + " " + key + ":\n  php': " + phpObs + "\n  java: " + javaObs);
                }
            } else if (expected.equals("LIMIT2")) {
                String phpObs = observe(st.status(), st.location(), st.body(), st.setCookies()).replace("limit=2abc", "limit=2");
                if (!phpObs.equals(javaObs)) {
                    problems.add(tag + " " + key + ":\n  php': " + phpObs + "\n  java: " + javaObs);
                }
            } else if (!expected.equals(javaObs)) {
                problems.add(tag + " " + key + ": expected (有意差异) " + expected + "\n  java: " + javaObs);
            }

            List<AdminGolden.Row> javaNow = javaRows();
            for (AdminGolden.Row jr : javaNow) {
                boolean written = s.rows0().stream().noneMatch(x -> x.id() == jr.id() && x.loginTime() == jr.loginTime());
                if (written && jr.loginTime() != 0 && (jr.loginTime() < RUN_STARTED || jr.loginTime() > after)) {
                    problems.add(tag + " " + key + ": 登录时间不在本次运行期间 " + jr.loginTime());
                }
            }
            if (st.rows() != null || JAVA_ROWS.containsKey(tag + ":" + key) || JAVA_ROWS.containsKey(key)) {
                List<AdminGolden.Row> phpNow = st.rows() == null ? previousPhp : st.rows();
                String adjust = JAVA_ROWS.getOrDefault(tag + ":" + key, JAVA_ROWS.get(key));
                List<String> want = expectedRows(adjust, s.rows0(), phpNow, previousJava, otherVersion.step(st.label()),
                        otherVersion);
                List<String> got = normalize(javaNow, s.rows0());
                if (!want.equals(got)) {
                    problems.add(tag + " " + key + " rows:\n  want: " + want + "\n  java: " + got);
                }
                previousPhp = phpNow;
            }
            previousJava = javaNow;
            compared++;
        }
        return compared;
    }

    // ------------------------------------------------------------------ 观察结果

    private static final Pattern TOKEN = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]+)\"");

    /** Ajax 请求拿到的页面是 HTML 的 JSON 字符串：解开。 */
    private static String unwrap(String body) {
        if (body.startsWith("\"<")) {
            return JsonMapper.builder().build().readTree(body).asString();
        }
        return body;
    }

    /**
     * 一个响应的可比较摘要：状态码、Location、is_iframe cookie、JSON 正文原样，或页面（含 Ajax 拿到的 JSON 字符串页面）中取出的结构。
     * 404 与 500 只比较状态码（旧版是 ThinkPHP 的异常页）。
     */
    static String observe(int status, String location, String body, List<String> setCookies) {
        StringBuilder sb = new StringBuilder("status=").append(status);
        if (location != null) {
            sb.append(" location=").append(location);
        }
        for (String c : setCookies) {
            if (c.startsWith("is_iframe=")) {
                sb.append(" cookie=").append(c.substring(0, c.indexOf(';')));
            }
        }
        if (status == 404 || status >= 500 || location != null) {
            return sb.toString();
        }
        if (body.startsWith("{")) {
            return sb.append(" json=").append(body).toString();
        }
        if (body.startsWith("\"<")) {
            return sb.append(" ajaxpage ").append(page(unwrap(body))).toString();
        }
        if (body.startsWith("<")) {
            return sb.append(" page ").append(page(body)).toString();
        }
        return sb.append(" text=").append(body).toString();
    }

    private static String page(String html) {
        if (html.contains("class=\"system-message\"")) {
            return "jump " + AdminHtml.jump(html);
        }
        if (html.contains("login-box")) {
            return "login " + AdminHtml.login(html);
        }
        if (html.contains("id=\"navBar\"")) {
            return "frame " + String.join(" / ", AdminHtml.menus(html))
                    .replace(" javascript:alert(1) ", " javascript:void(0); ");
        }
        if (html.contains("pageListForm")) {
            return "list " + String.join(" / ", AdminHtml.adminList(html)).replace(" [red]", "") + " / "
                    + AdminHtml.paging(html);
        }
        if (html.contains("role-list-form")) {
            return "info " + String.join(" / ", AdminHtml.adminInfo(html));
        }
        if (html.contains("name=\"quickmenu\"")) {
            return "quickmenu " + AdminHtml.quickmenu(html);
        }
        if (html.contains("layui-quote-nm mt10")) {
            return "welcome " + String.join(" / ", AdminHtml.welcome(html));
        }
        return "html";
    }

    // ------------------------------------------------------------------ mac_admin 的行

    private static void resetAdmins(List<AdminGolden.Row> rows) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM mac_admin");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mac_admin (" + ADMIN_COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (AdminGolden.Row r : rows) {
                    ps.setLong(1, r.id());
                    ps.setBytes(2, r.name().getBytes(StandardCharsets.UTF_8));
                    ps.setBytes(3, r.pwd().getBytes(StandardCharsets.UTF_8));
                    ps.setString(4, r.random());
                    ps.setInt(5, r.status());
                    ps.setString(6, r.auth());
                    ps.setLong(7, r.loginTime());
                    ps.setLong(8, r.loginIp());
                    ps.setLong(9, r.loginNum());
                    ps.setLong(10, r.lastLoginTime());
                    ps.setLong(11, r.lastLoginIp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static List<AdminGolden.Row> javaRows() throws SQLException {
        List<AdminGolden.Row> out = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + ADMIN_COLUMNS + " FROM mac_admin ORDER BY admin_id")) {
            while (rs.next()) {
                out.add(new AdminGolden.Row(rs.getLong(1), new String(rs.getBytes(2), StandardCharsets.UTF_8),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10), rs.getLong(11)));
            }
        }
        return out;
    }

    /**
     * 行的可比较形式：新插入的行 id 记为 NEW（两边的自增值不同）；登录时间与上次登录时间与场景开始时不同的记为 T（本次运行写入的
     * 时刻，两边不同）；随机串只看是否为空。新版写入的时刻另外检查在测试运行期间内。
     */
    private static List<String> normalize(List<AdminGolden.Row> rows, List<AdminGolden.Row> rows0) {
        List<String> out = new ArrayList<>();
        for (AdminGolden.Row r : rows) {
            AdminGolden.Row r0 = rows0.stream().filter(x -> x.id() == r.id()).findFirst().orElse(null);
            String login = r0 != null && r.loginTime() == r0.loginTime() ? String.valueOf(r.loginTime())
                    : r.loginTime() == 0 ? "0" : "T";
            String last = r0 != null && (r.lastLoginTime() == r0.lastLoginTime() || r.lastLoginTime() == r0.loginTime())
                    ? String.valueOf(r.lastLoginTime()) : r.lastLoginTime() == 0 ? "0" : "T";
            out.add((r0 != null ? String.valueOf(r.id()) : "NEW") + "|" + r.name() + "|" + r.pwd() + "|"
                    + (r.random().isEmpty() ? "" : "R") + "|" + r.status() + "|" + r.auth() + "|" + login + "|" + r.loginIp()
                    + "|" + r.loginNum() + "|" + last + "|" + r.lastLoginIp());
        }
        return out;
    }

    /** 新版请求后应有的行：默认与 PHP 相同；有意差异按 {@link #JAVA_ROWS} 调整。 */
    private static List<String> expectedRows(String adjust, List<AdminGolden.Row> rows0, List<AdminGolden.Row> php,
                                             List<AdminGolden.Row> previousJava, AdminGolden.Step otherStep,
                                             AdminGolden.Scenario other) {
        if (adjust == null) {
            return normalize(php, rows0);
        }
        switch (adjust) {
            case "php73":
                return normalize(otherStep.rows(), other.rows0());
            case "previous":
                return normalize(previousJava, rows0);
            case "only-first-login": {
                // 旧版按 账号 + 密码 + 状态 更新，同名同密码的第 6 行也被写了登录记录；新版只写登录的第 5 行
                List<AdminGolden.Row> fixed = new ArrayList<>();
                for (AdminGolden.Row r : php) {
                    fixed.add(r.id() == 6 ? rows0.stream().filter(x -> x.id() == 6).findFirst().orElseThrow() : r);
                }
                return normalize(fixed, rows0);
            }
            default: {
                // keep-extra-columns:<id>：旧版把 POST 里的其他列也写进去，新版只写名称、密码、状态、权限
                long id = Long.parseLong(adjust.substring(adjust.indexOf(':') + 1));
                AdminGolden.Row b = previousJava.stream().filter(x -> x.id() == id).findFirst().orElseThrow();
                List<AdminGolden.Row> fixed = new ArrayList<>();
                for (AdminGolden.Row r : php) {
                    fixed.add(r.id() != id ? r : new AdminGolden.Row(r.id(), r.name(), r.pwd(), b.random(), r.status(),
                            r.auth(), b.loginTime(), b.loginIp(), b.loginNum(), b.lastLoginTime(), b.lastLoginIp()));
                }
                return normalize(fixed, rows0);
            }
        }
    }

    // ------------------------------------------------------------------ 会话与验证码

    private static String cookie(MockHttpServletResponse r, String current) {
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith(AdminSessions.COOKIE + "=")) {
                String v = h.substring(AdminSessions.COOKIE.length() + 1, h.indexOf(';'));
                return v.isEmpty() ? null : v;
            }
        }
        return current;
    }

    private static void captcha(Variant v, String sid, String code) {
        SessionState s = v.sessions().find(sid).orElseThrow();
        Deque<Integer> idx = new ArrayDeque<>();
        for (char ch : code.toCharArray()) {
            idx.add(v.settings().captcha().codeSet().indexOf(ch));
        }
        SplittableRandom rest = new SplittableRandom(7);
        Captchas.issue(s, Captchas.DEFAULT_SLOT, v.settings().captcha(), v.sessions().nowEpochSeconds(),
                new RandomGenerator() {
                    @Override
                    public long nextLong() {
                        return rest.nextLong();
                    }

                    @Override
                    public int nextInt(int bound) {
                        return idx.isEmpty() ? rest.nextInt(bound) : idx.poll();
                    }
                });
    }

    // ------------------------------------------------------------------ ② 并发

    @Test
    @Order(2)
    void concurrentLoginsAreAllRecorded() throws Exception {
        Variant v = variant(Map.of());
        resetAdmins(List.of(new AdminGolden.Row(1, "admin", md5("admin888"), "", 1, "", 0, 0, 5, 0, 0)));
        AdminLoginService logins = v.app().getBean(AdminLoginService.class);
        var outcome = ConcurrentWrites.run(16, i -> logins.login("admin".getBytes(StandardCharsets.UTF_8),
                "admin888".getBytes(StandardCharsets.UTF_8), false, () -> true, 2130706433L).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(16);
        AdminGolden.Row row = javaRows().get(0);
        // 旧版是"读出的值 + 1"，并发时会丢计数；新版原子 +1
        assertThat(row.loginNum()).isEqualTo(21L);
        assertThat(row.loginIp()).isEqualTo(2130706433L);
    }

    @Test
    @Order(3)
    void loginRacingAPasswordChangeNeverRecordsAStalePassword() throws Exception {
        Variant v = variant(Map.of());
        AdminLoginService logins = v.app().getBean(AdminLoginService.class);
        AdminWriteMapper writer = v.app().getBean(AdminWriteMapper.class);
        for (int round = 0; round < 10; round++) {
            resetAdmins(List.of(new AdminGolden.Row(1, "admin", md5("admin888"), "", 1, "", 0, 0, 0, 0, 0)));
            var outcome = ConcurrentWrites.run(12, i -> {
                if (i == 5) {
                    writer.update(1, "admin", md5("newpass"), null, "");
                    return "changed";
                }
                AdminLoginService.LoginResult r = logins.login("admin".getBytes(StandardCharsets.UTF_8),
                        "admin888".getBytes(StandardCharsets.UTF_8), false, () -> true, 1L);
                return r.ok() ? "ok" : r.failure().name();
            });
            assertThat(outcome.failures()).isEmpty();
            AdminGolden.Row row = javaRows().get(0);
            assertThat(row.pwd()).isEqualTo(md5("newpass"));
            // 登录记录只写给通过了条件更新（密码仍是旧值）的那几次
            assertThat(row.loginNum()).isEqualTo(outcome.count("ok"));
            assertThat(outcome.values()).allMatch(x -> Set.of("ok", "ACCESS", "UPDATE", "changed").contains(x));
            // 改密之后旧密码一定登录不了
            assertThat(logins.login("admin".getBytes(StandardCharsets.UTF_8), "admin888".getBytes(StandardCharsets.UTF_8),
                    false, () -> true, 1L).failure()).isEqualTo(AdminLoginService.Failure.ACCESS);
            assertThat(logins.current(1, md5("admin888"))).as("用旧密码登录的会话被踢出（D2 ⑤）").isEmpty();
        }
    }

    // ------------------------------------------------------------------ ③ 回滚演练

    @Test
    @Order(4)
    void rollbackDrillRestoresEveryB12WriteWithDmlOnly() throws Exception {
        Variant v = variant(Map.of());
        resetAdmins(List.of(new AdminGolden.Row(1, "admin", md5("admin888"), "", 1, "", 0, 0, 0, 0, 0),
                new AdminGolden.Row(2, "editor", md5("ed pass"), "", 1, ",vod/data,", 0, 0, 0, 0, 0),
                new AdminGolden.Row(3, "viewer", md5("v"), "", 1, "", 0, 0, 0, 0, 0)));
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("DELETE FROM mac_vod_search");
            st.execute("INSERT INTO mac_vod_search (search_key, search_word, search_field, search_hit_count, "
                    + "search_last_hit_time, search_update_time, search_result_count, search_result_ids) VALUES "
                    + "('k-old', '旧', 'vod_name', 3, 1, 1, 0, ''), ('k-new', '新', 'vod_actor', 1, 1, "
                    + Instant.now().getEpochSecond() + ", 0, '')");
        }
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, TABLES);
        }
        AdminLoginService logins = v.app().getBean(AdminLoginService.class);
        AdminAccountService accounts = v.app().getBean(AdminAccountService.class);
        assertThat(logins.login(b("editor"), b("ed pass"), false, () -> true, 1L).ok()).isTrue();
        assertThat(accounts.save(new byte[0], b("drill"), b("p"), b("1"), List.of(b("vod/data"), b("index/welcome"))).ok())
                .isTrue();
        assertThat(accounts.save(b("2"), b("editor2"), b("q"), b("0"), List.of(b("index/welcome"))).ok()).isTrue();
        assertThat(accounts.setStatus(List.of(b("3")), b("admin_status"), b("0")).ok()).isTrue();
        assertThat(accounts.delete(List.of(b("3")), 1).ok()).isTrue();
        assertThat(v.app().getBean(ContentCacheMaintenance.class).clearVodSearchCache()).isTrue();
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, TABLES);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT search_key FROM mac_vod_search ORDER BY search_key")) {
                List<String> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
                // 过期的（更新时间早于 14 天）被清理，未过期的保留（旧 clearOldResult(true)）
                assertThat(keys).containsExactly("k-new");
            }
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, changed);
            assertThat(plan).allMatch(x -> x.sql().startsWith("INSERT") || x.sql().startsWith("UPDATE")
                    || x.sql().startsWith("DELETE"));
            DmlRestore.apply(c, plan);
            DatabaseSnapshot restored = DatabaseSnapshot.take(c, TABLES);
            SnapshotDiff diff = SnapshotDiff.between(before, restored);
            assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String md5(String s) {
        return LegacyInput.md5Hex(b(s));
    }

    // ------------------------------------------------------------------ 连接

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    private static Connection admin(String database) throws SQLException {
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
