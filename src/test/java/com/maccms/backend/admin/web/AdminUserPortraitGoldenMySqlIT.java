package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.time.Duration;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B14 后台会员表单"上传图片"（会员头像）与 PHP 实跑黄金的对照（./mvnw verify -Pmysql-it；23 文档第 7.7、9 节）。
 *
 * <p>黄金由 {@code src/test/php/admin_user_portrait_golden.php} 在 PHP 8.0 与 7.3 上生成：php-cgi 跑完整的 {@code _admin.php}
 * （登录、admin_auth、{@code upload/upload?flag=user&user_id=…} → {@code Upload::upload} 的 think-image 缩略图与 {@code Annex::infoData/saveData}，
 * 以及会员表单的打开与保存）。本测试新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt;、导入 install.sql，按场景的旧配置启动打开可写数据源与后台的
 * 应用（真实的内嵌 Tomcat，经 HTTP 发请求：multipart 的解析与 2 MB 上限与线上相同），每个场景先清空 {@code mac_user}、{@code mac_annex}、
 * {@code mac_admin} 并复位自增计数、写入同样的行与 {@code upload/} 文件，每步把时钟拨到 PHP 请求开始的秒，发出与 PHP 相同字节的请求，比较：
 * <ul>
 *   <li>状态码、Location、JSON 字节或页面结构（{@code AdminContentHtml}；上传结果的 {@code ctime} 为 PHP 落在 [started, after] 秒内、新版等于拨好的秒）；</li>
 *   <li>{@code mac_user} 的全部行逐列相同（旧后台的上传不改会员表，头像地址由表单保存写入）；</li>
 *   <li>{@code mac_annex} 的全部行除 {@code annex_size} 外逐列相同（同前台头像 D-B09-9：大小按新版重新编码的 JPEG），新版 {@code annex_size}
 *       等于新版头像文件的 KB；</li>
 *   <li>{@code upload/} 下的文件路径相同；新版的头像文件须是配置尺寸的 JPEG（只写重新编码后的图片）。</li>
 * </ul>
 * 有意差异（D-B14-10 等）按 {@link #JAVA} 断言新版的结果。之后是文件与数据库的回滚演练。结束时表结构指纹不变，删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminUserPortraitGoldenMySqlIT {

    static final String PHP80 = "admin/member/portrait-php80-golden.json";
    static final String PHP73 = "admin/member/portrait-php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    private static final String BOUNDARY = "----goldenboundary7d3a";
    private static final Map<String, String> TABLES = tables();
    private static final MutableClock CLOCK = new MutableClock(Instant.now().getEpochSecond());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static Map<String, Map<String, String>> columnKinds;
    private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();
    private static final List<Path> TEMP_DIRS = new ArrayList<>();

    record Variant(ConfigurableApplicationContext app, int port, Path siteRoot) {
    }

    /**
     * 新版的结果与 PHP 不同的步骤（有意差异）。{@code observation} 为新版的观察结果（null 时与 PHP 相同）；{@code after} 为这一步之后表与文件
     * 的来源：{@code "php"} 按 PHP 这一步，{@code "73"} 按 PHP 7.3 黄金的同一步，{@code "unchanged"} 与上一步相同（新版拒绝、不写盘不写库）。
     */
    record Expected(String observation, String after) {
    }

    static final Map<String, Expected> JAVA = new HashMap<>();

    static {
        String failed = "status=200 json={\"msg\":\"文件上传失败！\",\"code\":0,\"data\":[]}";
        // D-B09-14（同一规则）：PHP 8 上 think-image 的 GIF 解码器用了 PHP 8 已移除的 $x{0} 写法，整页 500、原图留在 <id>.jpg、不登记；
        // PHP 7.3 照常生成缩略图并登记。新版按 PHP 7.3 的结果
        JAVA.put("80:gif/upload", new Expected(null, "73"));
        // 扩展名在允许表内但内容不是图片：旧版 ThinkPHP File::move 的图片校验返回 false，随后 getSaveName() on bool 致命错误（两版都是 500，
        // 不落盘、不写库，原有头像文件不变）；新版"文件上传失败！"（同前台头像），同样不落盘、不写库
        JAVA.put("not-image/upload", new Expected(failed, "php"));
        JAVA.put("fail-keeps-old/upload", new Expected(failed, "php"));
        // D-B13-16（同一规则）：旧版先把上传的原文件写到 <id>.jpg（覆盖原有头像）再检查脚本标记、拒绝后文件留在磁盘上；新版检查通过才写入
        JAVA.put("script-mark/upload", new Expected(null, "unchanged"));
        // D-B14-10：会员号必须是已存在会员的十进制编号。旧版不查会员：缺失或 0 写 user/0/0.jpg，不存在、负数、小数、前导零、带字母时照样写出
        // 对应的文件并登记附件；PHP 7.3 上非数字写 user/0/abc.jpg、"../../1" 写到 upload/ 下的 1.jpg.jpg（目录之外），PHP 8 上非数字报 500。
        // 新版"文件上传失败！"，不落盘、不写库
        for (String s : List.of("id-missing", "id-zero", "id-absent", "id-text", "id-negative", "id-decimal", "id-leading",
                "id-leading-zero", "id-path")) {
            JAVA.put(s + "/upload", new Expected(failed, "unchanged"));
        }
    }

    private static Map<String, String> tables() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mac_user", "user_id");
        m.put("mac_annex", "annex_id");
        return m;
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
            assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
        }
    }

    /** 场景的旧配置（黄金记录的完整 extra/maccms.php）对应的应用（内嵌 Tomcat，随机端口），没有就启动一个。 */
    static Variant variant(String maccmsPhp) throws Exception {
        Variant v = VARIANTS.get(maccmsPhp);
        if (v != null) {
            return v;
        }
        Path dir = Files.createTempDirectory("maccms-b14-portrait-legacy");
        TEMP_DIRS.add(dir);
        Path extra = Files.createDirectories(dir.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        Files.writeString(extra.resolve("maccms.php"), maccmsPhp, StandardCharsets.UTF_8);
        Path site = Files.createTempDirectory("maccms-b14-portrait-site");
        TEMP_DIRS.add(site);
        Path upload = Files.createDirectories(site.resolve("upload"));
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + dir,
                "--maccms.upload.dir=" + upload, "--maccms.member.upload-dir=" + upload,
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
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("b14PortraitTestClock", CLOCK))
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        int port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        v = new Variant(app, port, site);
        VARIANTS.put(maccmsPhp, v);
        return v;
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void portraitUploadMatchesPhpGolden() throws Exception {
        JsonNode php80 = load(PHP80);
        JsonNode php73 = load(PHP73);
        assertThat(php80.get("php").asString()).startsWith("8.0");
        assertThat(php73.get("php").asString()).startsWith("7.3");
        List<String> problems = new ArrayList<>();
        int compared = 0;
        int uploaded = 0;
        for (JsonNode golden : List.of(php80, php73)) {
            String tag = golden == php80 ? "80" : "73";
            for (JsonNode s : golden.get("scenarios")) {
                int[] n = replay(tag, s, scenario(php73, s.get("label").asString()), problems);
                compared += n[0];
                uploaded += n[1];
            }
        }
        System.out.println("[mysql-it] admin portrait golden steps compared: " + compared + ", successful uploads: " + uploaded);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-portrait-golden-problems.txt"), String.join("\n\n", problems),
                StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA 断言），详见 target/admin-portrait-golden-problems.txt").isEmpty();
        assertThat(compared).isEqualTo(2 * stepCount(php80));
        assertThat(uploaded).as("两版里新版成功的上传").isEqualTo(26);
    }

    private static int stepCount(JsonNode golden) {
        int n = 0;
        for (JsonNode s : golden.get("scenarios")) {
            n += s.get("steps").size();
        }
        return n;
    }

    private static JsonNode scenario(JsonNode golden, String label) {
        for (JsonNode s : golden.get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalStateException(label);
    }

    /** 返回 [比较的步数, 新版成功的上传数]。 */
    private int[] replay(String tag, JsonNode s, JsonNode s73, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        resetFiles(v.siteRoot(), s.get("filesSeed"));
        Map<String, List<Map<String, String>>> before = rows(s.get("rows0"));
        Set<String> filesBefore = new TreeSet<>(fileNames(s.get("files0")));
        if (!javaRows().equals(before)) {
            problems.add(tag + ":" + label + " 场景开始时的行与 PHP 不同\n  php:  " + before + "\n  java: " + javaRows());
            return new int[] {0, 0};
        }
        int[] size = portraitSize(s.get("maccmsPhp").asString());
        Set<String> acceptedTimes = new HashSet<>();
        String sid = null;
        int n = 0;
        int ok = 0;
        int i = 0;
        for (JsonNode st : s.get("steps")) {
            String key = label + "/" + st.get("label").asString();
            Expected diff = JAVA.containsKey(tag + ":" + key) ? JAVA.get(tag + ":" + key) : JAVA.get(key);
            long started = st.get("started").asLong();
            long after = st.get("after").asLong();
            CLOCK.set(started);
            HttpResponse<byte[]> r = send(v, sid, st.get("request"));
            sid = cookie(r, sid);
            String body = ctime(new String(r.body(), StandardCharsets.UTF_8), started, started);
            String actual = AdminMemberGoldenMySqlIT.observe(r.statusCode(), r.headers().firstValue("Location").orElse(null), null, body);
            JsonNode ref = diff != null && "73".equals(diff.after()) ? s73.get("steps").get(i) : st;
            String expected = diff != null && diff.observation() != null ? diff.observation() : phpObservation(ref);
            if (!actual.equals(expected)) {
                problems.add(tag + ":" + key + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
            if (body.contains("\"code\":1,\"data\":{\"file\"")) {
                ok++;
            }
            boolean unchanged = diff != null && "unchanged".equals(diff.after());
            Map<String, List<Map<String, String>>> php = unchanged ? before : rows(ref.get("rows"));
            Set<String> phpFiles = unchanged ? filesBefore : new TreeSet<>(fileNames(ref.get("files")));
            Map<String, List<Map<String, String>>> java = javaRows();
            if (!php.get("mac_user").equals(java.get("mac_user"))) {
                problems.add(tag + ":" + key + " 表 mac_user\n  php:  " + php.get("mac_user") + "\n  java: " + java.get("mac_user"));
            }
            problems.addAll(diffAnnex(tag + ":" + key, php.get("mac_annex"), java.get("mac_annex"), ref.get("started").asLong(),
                    ref.get("after").asLong(), started, v.siteRoot(), acceptedTimes));
            Set<String> javaFiles = javaFiles(v.siteRoot());
            if (!javaFiles.equals(phpFiles)) {
                problems.add(tag + ":" + key + " upload/ 文件\n  php:  " + phpFiles + "\n  java: " + javaFiles);
            }
            problems.addAll(checkPortraitFiles(tag + ":" + key, v.siteRoot(), javaFiles, size));
            if (Files.exists(v.siteRoot().resolve("1.jpg.jpg")) || Files.exists(v.siteRoot().resolve("upload/1.jpg.jpg"))) {
                problems.add(tag + ":" + key + " 写到了 upload/user/ 之外");
            }
            before = php;
            filesBefore = phpFiles;
            n++;
            i++;
        }
        return new int[] {n, ok};
    }

    private static final Pattern CTIME = Pattern.compile("\"ctime\":([0-9]+)");

    /** 上传结果里的 {@code ctime}：落在 [from, to] 秒内时记为 CTIME。 */
    static String ctime(String body, long from, long to) {
        Matcher m = CTIME.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long t = Long.parseLong(m.group(1));
            m.appendReplacement(sb, t >= from && t <= to ? "\"ctime\":CTIME" : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String phpObservation(JsonNode st) {
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        body = ctime(body, st.get("started").asLong(), st.get("after").asLong());
        String location = st.get("location") == null || st.get("location").isNull() ? null : st.get("location").asString();
        return AdminMemberGoldenMySqlIT.observe(st.get("status").asInt(), location, null, body);
    }

    /** 按黄金记录的请求发给新版（与 PHP 相同的方法、路径、查询串与正文字节；multipart 用同一个分隔串）。 */
    private static HttpResponse<byte[]> send(Variant v, String sid, JsonNode req) throws Exception {
        String query = req.get("query").asString();
        URI uri = URI.create("http://127.0.0.1:" + v.port() + E + req.get("path").asString() + (query.isEmpty() ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
        if (req.get("ajax").asBoolean()) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        JsonNode mp = req.get("multipart");
        if ("POST".equals(req.get("method").asString())) {
            byte[] body;
            if (mp != null && !mp.isNull()) {
                body = multipart(mp);
                b.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            } else {
                body = req.get("post").asString().getBytes(StandardCharsets.ISO_8859_1);
                b.header("Content-Type", "application/x-www-form-urlencoded");
            }
            b.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.GET();
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 黄金脚本 multipart() 的同一正文（文件内容为 content 加 zeros 个 \0）。 */
    static byte[] multipart(JsonNode parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (JsonNode p : parts) {
            out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (p.has("filename")) {
                out.write(("Content-Disposition: form-data; name=\"" + p.get("name").asString() + "\"; filename=\""
                        + p.get("filename").asString() + "\"\r\nContent-Type: " + p.get("type").asString() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(Base64.getDecoder().decode(p.get("content").asString()));
                out.write(new byte[p.get("zeros").asInt()]);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(("Content-Disposition: form-data; name=\"" + p.get("name").asString() + "\"\r\n\r\n"
                        + p.get("value").asString() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String cookie(HttpResponse<?> r, String current) {
        for (String h : r.headers().allValues("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                String v = h.substring("mac_admin_sid=".length(), h.indexOf(';'));
                return v.isEmpty() || h.contains("Max-Age=0") ? null : v;
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ 比较

    /**
     * {@code mac_annex}：行数与主键相同；除 {@code annex_size} 外逐列相同（{@code annex_time}：PHP 在 [started, after] 内、新版等于拨好的秒，
     * 认定后记进 {@code acceptedTimes} 供之后的步骤使用；场景预置的旧行原样）；新版头像行的 {@code annex_size} 等于新版头像文件的 KB。
     */
    private static List<String> diffAnnex(String key, List<Map<String, String>> php, List<Map<String, String>> java, long started,
                                          long after, long javaTime, Path site, Set<String> acceptedTimes) throws IOException {
        List<String> out = new ArrayList<>();
        if (php.size() != java.size()) {
            return List.of(key + " 表 mac_annex 行数\n  php:  " + php + "\n  java: " + java);
        }
        for (int k = 0; k < php.size(); k++) {
            Map<String, String> p = php.get(k);
            Map<String, String> j = java.get(k);
            for (String col : p.keySet()) {
                String pv = p.get(col);
                String jv = j.get(col);
                if (col.equals("annex_size")) {
                    continue;
                }
                if (col.equals("annex_time") && !pv.equals(jv)) {
                    long t = Long.parseLong(pv);
                    if (acceptedTimes.contains(pv + "=" + jv)) {
                        continue;
                    }
                    if (t >= started && t <= after && Long.parseLong(jv) == javaTime) {
                        acceptedTimes.add(pv + "=" + jv);
                        continue;
                    }
                }
                if (!pv.equals(jv)) {
                    out.add(key + " 表 mac_annex 行 " + p.get("annex_id") + " 列 " + col + "\n  php:  " + pv + "\n  java: " + jv);
                }
            }
            String file = new String(HexFormat.of().parseHex(j.get("annex_file")), StandardCharsets.UTF_8);
            Path f = site.resolve(file);
            if (file.startsWith("upload/user/") && Long.parseLong(j.get("annex_time")) == javaTime && Files.isRegularFile(f)) {
                long kb = BigDecimal.valueOf(Files.size(f) / 1024.0).setScale(2, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                if (Long.parseLong(j.get("annex_size")) != kb) {
                    out.add(key + " 新版头像行 annex_size=" + j.get("annex_size") + "，文件 " + Files.size(f) + " 字节（应为 " + kb + "）");
                }
            }
        }
        return out;
    }

    /** 新版写下的头像文件必须是配置尺寸的 JPEG（只写重新编码后的图片）。 */
    private static List<String> checkPortraitFiles(String key, Path site, Set<String> files, int[] size) throws IOException {
        List<String> out = new ArrayList<>();
        for (String rel : files) {
            if (!rel.startsWith("upload/user/")) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(site.resolve(rel));
            if (bytes.length == 3 && new String(bytes, StandardCharsets.US_ASCII).equals("old")) {
                continue; // 场景预置、没有被覆盖的旧文件
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            boolean jpeg = bytes.length > 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
            if (!jpeg || img == null || img.getWidth() != size[0] || img.getHeight() != size[1]) {
                out.add(key + " 新版头像文件 " + rel + " 不是 " + size[0] + "×" + size[1] + " 的 JPEG");
            }
        }
        return out;
    }

    private static final Pattern PORTRAIT_SIZE = Pattern.compile("'portrait_size' => '([0-9]+)(?:x([0-9]+))?'");

    private static int[] portraitSize(String maccmsPhp) {
        Matcher m = PORTRAIT_SIZE.matcher(maccmsPhp);
        if (!m.find()) {
            throw new IllegalStateException("portrait_size");
        }
        int w = Integer.parseInt(m.group(1));
        return new int[] {w, m.group(2) == null ? w : Integer.parseInt(m.group(2))};
    }

    // ------------------------------------------------------------------ ② 回滚演练

    /**
     * 文件与数据库的回滚：快照 {@code mac_user}、{@code mac_annex} 与 {@code upload/}（切换前先备份 upload/，22 文档第 11 节）→ 经后台 HTTP 上传
     * 两位会员的头像（一位覆盖已有的头像文件）并保存表单 → {@code DmlRestore} 只用 DML 恢复两张表、按备份恢复 {@code upload/}（删除新写的文件、
     * 写回被覆盖的文件）→ 逐行、逐文件与快照相同 → 表结构指纹不变。
     */
    @Test
    @Order(2)
    void rollbackRestoresPortraitFilesAndRowsWithDmlAndTheUploadBackup() throws Exception {
        JsonNode flow = scenario(load(PHP80), "flow");
        Variant v = variant(flow.get("maccmsPhp").asString());
        seed(flow.get("seed"));
        resetFiles(v.siteRoot(), JSON.readTree("{\"upload/user/1/1.jpg\":\"" + Base64.getEncoder().encodeToString("old".getBytes())
                + "\"}"));
        List<String> tables = List.of("mac_user", "mac_annex");
        Map<String, byte[]> filesBefore = fileContents(v.siteRoot());
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        CLOCK.set(Instant.now().getEpochSecond());
        JsonNode steps = flow.get("steps");
        String sid = cookie(send(v, null, steps.get(0).get("request")), null);
        JsonNode uploadReq = steps.get(2).get("request");
        assertThat(new String(send(v, sid, uploadReq).body(), StandardCharsets.UTF_8)).contains("\"code\":1");
        JsonNode upload1 = JSON.readTree(uploadReq.toString().replace("user_id=15", "user_id=1"));
        assertThat(new String(send(v, sid, upload1).body(), StandardCharsets.UTF_8)).contains("upload\\/user\\/1\\/1.jpg");
        assertThat(new String(send(v, sid, steps.get(3).get("request")).body(), StandardCharsets.UTF_8)).contains("\"code\":1");
        assertThat(fileContents(v.siteRoot())).isNotEqualTo(filesBefore);
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
            System.out.println("[mysql-it] B14 portrait rollback drill restored with " + plan.size() + " DML statements");
        }
        restoreFiles(v.siteRoot(), filesBefore);
        Map<String, byte[]> filesAfter = fileContents(v.siteRoot());
        assertThat(filesAfter.keySet()).isEqualTo(filesBefore.keySet());
        filesBefore.forEach((k, bytes) -> assertThat(filesAfter.get(k)).as(k).isEqualTo(bytes));
    }

    /** upload/ 下全部文件（含 . 开头的临时文件，用来证明失败时没有留下）的内容。 */
    private static Map<String, byte[]> fileContents(Path site) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(site.resolve("upload"))) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(site.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    private static void restoreFiles(Path site, Map<String, byte[]> backup) throws IOException {
        for (String rel : fileContents(site).keySet()) {
            if (!backup.containsKey(rel)) {
                Files.delete(site.resolve(rel));
            }
        }
        for (Map.Entry<String, byte[]> e : backup.entrySet()) {
            Path p = site.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static void seed(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String t : List.of("mac_user", "mac_annex", "mac_admin")) {
                    st.execute("DELETE FROM " + t);
                    st.execute("ALTER TABLE " + t + " AUTO_INCREMENT = 1");
                }
                st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                        + "(1, 'admin', '" + md5("admin888") + "', 1, ''), "
                        + "(2, 'editor', '" + md5("ed pass") + "', 1, ',user/data,user/info,upload/upload,'), "
                        + "(3, 'viewer', '" + md5("vw pass") + "', 1, ',user/data,user/info,')");
            }
            for (Map.Entry<String, JsonNode> e : seed.properties()) {
                String table = e.getKey();
                for (JsonNode row : e.getValue()) {
                    List<String> cols = new ArrayList<>();
                    row.properties().forEach(p -> cols.add(p.getKey()));
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int k = 0; k < cols.size(); k++) {
                            JsonNode val = row.get(cols.get(k));
                            String kind = columnKinds.get(table).get(cols.get(k));
                            if (kind.equals("int")) {
                                ps.setLong(k + 1, val.asLong());
                            } else if (kind.equals("decimal")) {
                                ps.setString(k + 1, val.asString());
                            } else {
                                ps.setBytes(k + 1, HexFormat.of().parseHex(val.asString()));
                            }
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static void resetFiles(Path site, JsonNode files) throws IOException {
        Path upload = site.resolve("upload");
        deleteTree(upload);
        Files.createDirectories(upload);
        for (Map.Entry<String, JsonNode> e : files.properties()) {
            Path p = site.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, Base64.getDecoder().decode(e.getValue().asString()));
        }
    }

    /** 黄金里的行（整数列为整数、decimal 为字符串、其余十六进制）统一成文字。 */
    private static Map<String, List<Map<String, String>>> rows(JsonNode tables) {
        Map<String, List<Map<String, String>>> out = new TreeMap<>();
        for (String t : TABLES.keySet()) {
            List<Map<String, String>> list = new ArrayList<>();
            for (JsonNode row : tables.get(t)) {
                Map<String, String> r = new LinkedHashMap<>();
                row.properties().forEach(p -> r.put(p.getKey(), p.getValue().asString()));
                list.add(r);
            }
            out.put(t, list);
        }
        return out;
    }

    private static Map<String, List<Map<String, String>>> javaRows() throws SQLException {
        Map<String, List<Map<String, String>>> out = new TreeMap<>();
        try (Connection c = admin(dbName)) {
            for (Map.Entry<String, String> t : TABLES.entrySet()) {
                List<Map<String, String>> list = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM " + t.getKey() + " ORDER BY " + t.getValue())) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, String> r = new LinkedHashMap<>();
                        for (int k = 1; k <= md.getColumnCount(); k++) {
                            String col = md.getColumnLabel(k);
                            String kind = columnKinds.get(t.getKey()).get(col);
                            r.put(col, kind.equals("text") ? HexFormat.of().formatHex(rs.getBytes(k)) : rs.getString(k));
                        }
                        list.add(r);
                    }
                }
                out.put(t.getKey(), list);
            }
        }
        return out;
    }

    private static List<String> fileNames(JsonNode files) {
        List<String> out = new ArrayList<>();
        files.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    /** upload/ 下的文件（相对站点根）；. 开头的临时文件也算（失败时不能留下）。 */
    private static Set<String> javaFiles(Path site) throws IOException {
        return new TreeSet<>(fileContents(site).keySet());
    }

    private static Map<String, Map<String, String>> columnKinds(Connection c) throws SQLException {
        Map<String, Map<String, String>> out = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE()")) {
            while (rs.next()) {
                String type = rs.getString(3);
                String kind = List.of("tinyint", "smallint", "mediumint", "int", "bigint").contains(type) ? "int"
                        : type.equals("decimal") ? "decimal" : "text";
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), kind);
            }
        }
        return out;
    }

    private static JsonNode load(String resource) {
        try (InputStream in = AdminUserPortraitGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少黄金 " + resource);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private static List<String> installStatements() throws IOException {
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
