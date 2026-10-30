package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.schema.SchemaFingerprint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D-B09-9（B13，MACC-37）前台头像上传登记 {@code mac_annex} 与 PHP 实跑黄金的逐列对照（./mvnw verify -Pmysql-it；22 文档第 7.10、9 节）。
 *
 * <p>黄金由 {@code src/test/php/portrait_annex_golden.php} 在 PHP 8.0 与 7.3 上生成：php-cgi 跑完整的 {@code index.php/user/portrait}
 * （label_user 按 cookie 认出会员 → {@code Upload::upload} 的 think-image 缩略图、{@code UPDATE mac_user}、{@code Annex::infoData/saveData}）。
 * 本测试新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt;、导入 install.sql，按场景的旧配置启动打开可写数据源与前台的应用上下文，每个场景
 * 先清空 {@code mac_user}、{@code mac_annex} 并复位自增计数、写入同样的行与 {@code upload/} 文件，每步把时钟拨到 PHP 请求开始的秒，以同样的
 * cookie 与正文字节 POST {@code /index.php/user/portrait}，比较：
 * <ul>
 *   <li>JSON 正文（{@code file} 末尾的 4 位随机数记为 RND）；</li>
 *   <li>{@code mac_annex} 的全部行、除 {@code annex_size} 外逐列相同（路线图 B13 验收：大小按新版重新编码的 JPEG）；{@code annex_time} 为
 *       PHP 落在 [started, after] 秒内、新版等于拨好的秒；新版的 {@code annex_size} 另须等于新版头像文件的 KB（两位小数后按整数列取整）；</li>
 *   <li>{@code mac_user} 的全部行逐列相同；</li>
 *   <li>{@code upload/} 下的文件路径相同（内容不比：新版只写重新编码的 JPEG，D-B09-9/-13）；新版的头像文件须是 100×100 的 JPEG。</li>
 * </ul>
 * 有意差异按 {@link #JAVA} 断言新版的结果。结束时表结构指纹不变，删库并确认不存在。
 */
class PortraitAnnexGoldenMySqlIT {

    static final String PHP80 = "member/portrait-annex-php80-golden.json";
    static final String PHP73 = "member/portrait-annex-php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Map<String, String> TABLES = Map.of("mac_user", "user_id", "mac_annex", "annex_id");
    private static final MutableClock CLOCK = new MutableClock(Instant.now().getEpochSecond());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static Map<String, Map<String, String>> columnKinds;
    private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();
    private static final List<Path> TEMP_DIRS = new ArrayList<>();

    record Variant(ConfigurableApplicationContext app, MockMvc mvc, Path siteRoot) {
    }

    /** 新版的结果与 PHP 不同的步骤（有意差异）。 */
    record Expected(String body, String rowsFrom) {
    }

    /**
     * 键为 {@code 场景/步骤}（两版都适用）或 {@code 80:场景/步骤}（只对 PHP 8.0）。{@code body} 为新版的 JSON 正文（RND 同上）；{@code rowsFrom}
     * 为 {@code "73"} 时这一步之后的表与文件按 PHP 7.3 黄金的同一步比较，为 null 时按 PHP。
     */
    static final Map<String, Expected> JAVA = new HashMap<>();

    static {
        String ok1 = "{\"msg\":\"文件上传成功！\",\"code\":1,\"file\":\"\\/upload\\/user\\/1\\/801.jpg?RND\"}";
        String ok5 = "{\"msg\":\"文件上传成功！\",\"code\":1,\"file\":\"\\/upload\\/user\\/5\\/15.jpg?RND\"}";
        // PHP 8：think-image 的 GIF 解码器用了 PHP 8 已移除的 $x{0} 写法（Decoder.php:165），整页 500、原图留在 <id>.jpg、不更新 user_portrait、
        // 不登记；PHP 7.3 照常生成缩略图并登记。新版按 PHP 7.3 的结果（D-B09-14）
        JAVA.put("80:gif/upload", new Expected(ok1, "73"));
        JAVA.put("80:dir5/upload", new Expected(ok5, "73"));
        // 扩展名在允许表内但内容不是图片：旧版 ThinkPHP File::move 的图片校验返回 false，随后 getSaveName() on bool 致命错误（两版都是 500，
        // 不落盘、不写库）；新版"文件上传失败！"（同 make_thumb_faild 的文案，18 文档第 3.8 节），同样不落盘、不写库
        JAVA.put("not-image/upload", new Expected("{\"msg\":\"文件上传失败！\",\"code\":0,\"file\":\"\\/?RND\"}", null));
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

    /** 场景的旧配置（黄金记录的完整 extra/maccms.php）对应的应用上下文，没有就启动一个。 */
    static Variant variant(String maccmsPhp) throws Exception {
        Variant v = VARIANTS.get(maccmsPhp);
        if (v != null) {
            return v;
        }
        Path dir = Files.createTempDirectory("maccms-portrait-legacy");
        TEMP_DIRS.add(dir);
        Path extra = Files.createDirectories(dir.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        Files.writeString(extra.resolve("maccms.php"), maccmsPhp, StandardCharsets.UTF_8);
        Path site = Files.createTempDirectory("maccms-portrait-site");
        TEMP_DIRS.add(site);
        Path upload = Files.createDirectories(site.resolve("upload"));
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.site.enabled=true", "--maccms.site.name=示例站点",
                "--maccms.site.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + dir,
                "--maccms.member.upload-dir=" + upload, "--maccms.upload.dir=" + upload,
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
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("portraitTestClock", CLOCK))
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
        v = new Variant(app, mvc, site);
        VARIANTS.put(maccmsPhp, v);
        return v;
    }

    // ------------------------------------------------------------------ 复放

    @Test
    void portraitAnnexMatchesPhpGolden() throws Exception {
        JsonNode php80 = load(PHP80);
        JsonNode php73 = load(PHP73);
        assertThat(php80.get("php").asString()).startsWith("8.0");
        assertThat(php73.get("php").asString()).startsWith("7.3");
        List<String> problems = new ArrayList<>();
        int compared = 0;
        int registered = 0;
        for (JsonNode golden : List.of(php80, php73)) {
            String tag = golden == php80 ? "80" : "73";
            for (JsonNode s : golden.get("scenarios")) {
                JsonNode other = scenario(php73, s.get("label").asString());
                int[] n = replay(tag, s, other, problems);
                compared += n[0];
                registered += n[1];
            }
        }
        System.out.println("[mysql-it] portrait annex golden steps compared: " + compared + ", annex rows registered: " + registered);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "portrait-annex-golden-problems.txt"), String.join("\n\n", problems),
                StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA 断言），详见 target/portrait-annex-golden-problems.txt")
                .isEmpty();
        assertThat(compared).isEqualTo(36);
        assertThat(registered).as("两版里新版新登记的头像附件行").isEqualTo(18);
    }

    private static JsonNode scenario(JsonNode golden, String label) {
        for (JsonNode s : golden.get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalStateException(label);
    }

    /** 返回 [比较的步数, 新版这一场景里新登记的头像附件行数]。 */
    private int[] replay(String tag, JsonNode s, JsonNode s73, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        resetFiles(v.siteRoot(), s.get("filesSeed"));
        Map<String, List<Map<String, String>>> before = rows(s.get("rows0"));
        Map<String, List<Map<String, String>>> java0 = javaRows();
        if (!java0.equals(before)) {
            problems.add(tag + ":" + label + " 场景开始时的行与 PHP 不同\n  php:  " + before + "\n  java: " + java0);
            return new int[] {0, 0};
        }
        // 前面步骤里已认定相同的 (PHP 时间, 新版时间)：之后的步骤里这些行原样留在表中
        java.util.Set<String> acceptedTimes = new java.util.HashSet<>();
        int n = 0;
        int newRows = 0;
        int i = 0;
        for (JsonNode st : s.get("steps")) {
            String key = label + "/" + st.get("label").asString();
            Expected diff = JAVA.containsKey(tag + ":" + key) ? JAVA.get(tag + ":" + key) : JAVA.get(key);
            long started = st.get("started").asLong();
            long after = st.get("after").asLong();
            CLOCK.set(started);
            MockHttpServletResponse r = send(v, st.get("request"));
            String body = rnd(r.getContentAsString(StandardCharsets.UTF_8));
            String expectedBody = diff != null && diff.body() != null ? diff.body() : phpBody(st);
            if (!body.equals(expectedBody)) {
                problems.add(tag + ":" + key + " 正文\n  expected: " + expectedBody + "\n  actual:   " + body
                        + "  (status " + r.getStatus() + ")");
            }
            // 这一步之后应有的表与文件
            JsonNode ref = diff != null && "73".equals(diff.rowsFrom()) ? s73.get("steps").get(i) : st;
            Map<String, List<Map<String, String>>> php = rows(ref.get("rows"));
            java.util.Set<String> phpFiles = new java.util.TreeSet<>(fileNames(ref.get("files")));
            long refStarted = ref.get("started").asLong();
            long refAfter = ref.get("after").asLong();
            Map<String, List<Map<String, String>>> java = javaRows();
            problems.addAll(diffUsers(tag + ":" + key, php.get("mac_user"), java.get("mac_user")));
            List<String> annexDiff = diffAnnex(tag + ":" + key, php.get("mac_annex"), java.get("mac_annex"), refStarted, refAfter,
                    started, v.siteRoot(), acceptedTimes);
            problems.addAll(annexDiff);
            java.util.Set<String> javaFiles = javaFiles(v.siteRoot());
            if (!javaFiles.equals(phpFiles)) {
                problems.add(tag + ":" + key + " upload/ 文件\n  php:  " + phpFiles + "\n  java: " + javaFiles);
            }
            problems.addAll(checkPortraitFiles(tag + ":" + key, v.siteRoot(), javaFiles));
            newRows += Math.max(0, java.get("mac_annex").size() - before.get("mac_annex").size());
            before = php;
            n++;
            i++;
        }
        return new int[] {n, newRows};
    }

    private static final Pattern RND = Pattern.compile("\\?[0-9]{4}\"");

    static String rnd(String body) {
        return RND.matcher(body).replaceAll("?RND\"");
    }

    private static String phpBody(JsonNode st) {
        return rnd(new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8));
    }

    private static MockHttpServletResponse send(Variant v, JsonNode req) throws Exception {
        java.net.URI uri = java.net.URI.create("/index.php" + req.get("path").asString());
        AbstractMockHttpServletRequestBuilder<?> b;
        JsonNode mp = req.get("multipart");
        if (mp != null && !mp.isNull()) {
            MockMultipartHttpServletRequestBuilder m = MockMvcRequestBuilders.multipart(uri);
            for (JsonNode part : mp) {
                if (part.has("filename")) {
                    m.file(new MockMultipartFile(part.get("name").asString(), part.get("filename").asString(),
                            part.get("type").asString(), Base64.getDecoder().decode(part.get("content").asString())));
                } else {
                    m.param(part.get("name").asString(), part.get("value").asString());
                }
            }
            b = m;
        } else {
            b = MockMvcRequestBuilders.post(uri).contentType("application/x-www-form-urlencoded")
                    .content(req.get("post").asString().getBytes(StandardCharsets.ISO_8859_1));
        }
        b.header("X-Requested-With", "XMLHttpRequest").header("Host", "www.example.test");
        JsonNode cookie = req.get("cookie");
        if (cookie != null && !cookie.isNull()) {
            b.header("Cookie", cookie.asString());
        }
        return v.mvc().perform(b).andReturn().getResponse();
    }

    // ------------------------------------------------------------------ 比较

    private static List<String> diffUsers(String key, List<Map<String, String>> php, List<Map<String, String>> java) {
        if (php.equals(java)) {
            return List.of();
        }
        return List.of(key + " 表 mac_user\n  php:  " + php + "\n  java: " + java);
    }

    /**
     * {@code mac_annex}：行数与主键相同；除 {@code annex_size} 外逐列相同（{@code annex_time}：PHP 在 [started, after] 内、新版等于拨好的秒，
     * 认定后记进 {@code acceptedTimes} 供之后的步骤使用；场景预置的旧行原样）；新版头像行的 {@code annex_size} 等于新版头像文件的 KB（两位小数后按整数列取整）。
     */
    private static List<String> diffAnnex(String key, List<Map<String, String>> php, List<Map<String, String>> java,
                                          long started, long after, long javaTime, Path site,
                                          java.util.Set<String> acceptedTimes) throws IOException {
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
                // 新版这一步登记的头像行：大小按新版文件
                long kb = BigDecimal.valueOf(Files.size(f) / 1024.0).setScale(2, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                if (Long.parseLong(j.get("annex_size")) != kb) {
                    out.add(key + " 新版头像行 annex_size=" + j.get("annex_size") + "，文件 " + Files.size(f) + " 字节（应为 " + kb + "）");
                }
            }
        }
        return out;
    }

    /** 新版写下的头像文件必须是 100×100 的 JPEG（只写重新编码后的图片，D-B09-9）。 */
    private static List<String> checkPortraitFiles(String key, Path site, java.util.Set<String> files) throws IOException {
        List<String> out = new ArrayList<>();
        for (String rel : files) {
            if (!rel.startsWith("upload/user/")) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(site.resolve(rel));
            if (bytes.length == 3 && new String(bytes, StandardCharsets.US_ASCII).equals("old")) {
                continue; // 场景预置、尚未被覆盖的旧文件
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            boolean jpeg = bytes.length > 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
            if (!jpeg || img == null || img.getWidth() != 100 || img.getHeight() != 100) {
                out.add(key + " 新版头像文件 " + rel + " 不是 100×100 的 JPEG");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 夹具

    private static void seed(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String t : List.of("mac_user", "mac_annex")) {
                    st.execute("DELETE FROM " + t);
                    st.execute("ALTER TABLE " + t + " AUTO_INCREMENT = 1");
                }
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

    private static java.util.Set<String> javaFiles(Path site) throws IOException {
        java.util.Set<String> out = new java.util.TreeSet<>();
        Path upload = site.resolve("upload");
        try (Stream<Path> s = Files.walk(upload)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = site.relativize(p).toString().replace('\\', '/');
                if (!rel.substring(rel.lastIndexOf('/') + 1).startsWith(".")) {
                    out.add(rel);
                }
            }
        }
        return out;
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
        try (InputStream in = PortraitAnnexGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少黄金 " + resource);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
