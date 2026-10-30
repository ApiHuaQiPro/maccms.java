package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.AddonMarketService;
import com.maccms.backend.admin.application.AddonPackage;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.config.AdminConfiguration;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import com.maccms.backend.integration.addonmarket.FastadminAddonMarket;
import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B29 插件市场安装/升级的 PHP 黄金复放（不连库；35 文档第 9 节）。
 *
 * <p>黄金 {@code admin/addonmarket/php80-golden.json}、{@code php73-golden.json} 由 {@code src/test/php/addon_market_golden.php} 用真实的
 * 旧后台（php-cgi + ThinkPHP + fastadmin-addons，临时站点副本里加了 {@code fast\Http} 测试替身）对本机模拟市场生成。这里按同样的初始文件建一个
 * 临时站点，每个场景前还原；本机模拟市场（{@link LoopbackMockServer}）按黄金里同一份下载计划回复、返回同样字节的插件包；把同样的请求发给新版，
 * 逐步比较：状态码、正文（JSON 里的 {@code time} 不比）、模拟市场收到的请求（路径与查询串、{@code X-REQUESTED-WITH}）、这一步之后站点里变化的
 * 文件（SHA-256；{@code runtime/addons/} 的备份只比文件名）。有意差异（35 文档第 10 节）在 {@link #expected} 里逐条换算。
 * 另断言系统临时目录里没有留下解包用的临时文件。
 */
@WebMvcTest(controllers = AdminController.class, properties = {
        "maccms.admin.enabled=true",
        "maccms.admin.entry=/_admin.php",
        "maccms.admin.base-url=http://www.example.test/"})
@Import({AdminConfiguration.class, ClientIpConfiguration.class, AddonMarketGoldenTest.Fakes.class})
class AddonMarketGoldenTest {

    static final String E = "/_admin.php";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode G80 = load("admin/addonmarket/php80-golden.json");
    private static final JsonNode G73 = load("admin/addonmarket/php73-golden.json");
    private static final Path SITE = createSite();
    private static final LoopbackMockServer MOCK = LoopbackMockServer.start();
    private static final ConcurrentLinkedQueue<MockResponse> DOWNLOADS = new ConcurrentLinkedQueue<>();
    /** 当前场景的市场地址（{@code fastadmin.api_url}）：{@code {mock}}、固定地址或未配置（空串）。 */
    private static volatile String apiUrl = "";

    static {
        MOCK.stub("GET", "/addon/download", r -> {
            MockResponse m = DOWNLOADS.poll();
            return m == null ? MockResponse.text(599, "unplanned") : m;
        });
        G80.get("packages").properties().forEach(e -> MOCK.stub("GET", "/pkg/" + e.getKey(),
                new MockResponse(200, Map.of("Content-Type", "application/zip"), Base64.getDecoder().decode(e.getValue().asString()),
                        Duration.ZERO)));
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeAdminTable fakeAdminTable() {
            FakeAdminTable t = new FakeAdminTable();
            t.put(1, "admin", LegacyInput.md5Hex("admin888".getBytes(StandardCharsets.UTF_8)), 1, "");
            return t;
        }

        /** 按场景切换市场地址的插件市场（下载仍由 {@code integration.addonmarket} 经出站客户端发出，只连本机）。 */
        @Bean
        AddonMarket addonMarket() {
            OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                    IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(10), 4096, List.of());
            return new AddonMarket() {
                @Override
                public byte[] download(String name, Map<String, String> extend) {
                    return new FastadminAddonMarket(client, apiUrl, maxPackageBytes()).download(name, extend);
                }

                @Override
                public int maxPackageBytes() {
                    return 16 * 1024 * 1024;
                }
            };
        }
    }

    @DynamicPropertySource
    static void legacyDir(DynamicPropertyRegistry r) {
        r.add("maccms.legacy-config.dir", () -> SITE.resolve("application").toString());
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentCacheMaintenance contentCache;

    @AfterAll
    static void tearDown() throws IOException {
        MOCK.close();
        deleteTree(SITE);
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private static JsonNode load(String name) {
        try (InputStream in = AddonMarketGoldenTest.class.getResourceAsStream("/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 站点

    private static Path createSite() {
        try {
            Path site = Files.createTempDirectory("maccms-b29-site");
            reset(site);
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 与黄金脚本的 base 相同的被跟踪文件（仓库文件，CRLF 换成 LF）；新版启动只另需 {@code extra/maccms.php}。 */
    static void reset(Path site) throws IOException {
        for (String dir : List.of("addons", "static", "application", "runtime")) {
            deleteTree(site.resolve(dir));
        }
        Path repo = Path.of("php");
        for (String f : List.of("addons/adminloginbg/Adminloginbg.php", "addons/adminloginbg/controller/Index.php",
                "addons/adminloginbg/info.ini", "addons/adminloginbg/config.php", "static/js/addons.js", "application/extra/addons.php")) {
            byte[] b = Files.readAllBytes(repo.resolve(f));
            if (!f.endsWith(".php") || f.startsWith("application/extra/") || f.endsWith("config.php")) {
                b = new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            }
            Path p = site.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, b);
        }
        Files.writeString(site.resolve("application/extra/maccms.php"),
                "<?php\nreturn array (\n  'app' => \n  array (\n    'admin_login_verify' => '0',\n    'lang' => 'zh-cn',\n"
                        + "    'pagesize' => '20',\n  ),\n);", StandardCharsets.UTF_8);
        Files.createDirectories(site.resolve("runtime"));
    }

    /** 与黄金脚本 site_state 相同的范围：addons/、static/、application/（不含 database.php）、runtime/addons/。 */
    static Map<String, String> state(Path site) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String dir : List.of("addons", "static", "application", "runtime/addons")) {
            Path d = site.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String rel = dir + "/" + d.relativize(p).toString().replace('\\', '/');
                    if (rel.equals("application/database.php") || rel.equals("application/extra/maccms.php")) {
                        continue;
                    }
                    rel = rel.replaceAll("^(runtime/addons/[A-Za-z0-9_]+-backup-)[0-9]{14}(\\.zip)$", "$1{ts}$2");
                    out.put(rel, rel.startsWith("runtime/addons/") && rel.contains("-backup-") ? "zip" : sha256(Files.readAllBytes(p)));
                }
            }
        }
        for (String f : List.of("evil-traversal.txt")) {
            if (Files.isRegularFile(site.resolve(f))) {
                out.put(f, sha256(Files.readAllBytes(site.resolve(f))));
            }
            if (Files.isRegularFile(site.getParent().resolve(f))) {
                out.put("../" + f, sha256(Files.readAllBytes(site.getParent().resolve(f))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 复放

    @Test
    void scenariosMatchPhpGolden() throws Exception {
        long tmpBefore = leftoverTempFiles();
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (JsonNode g : List.of(G80, G73)) {
            String tag = g == G80 ? "80" : "73";
            for (JsonNode s : g.get("scenarios")) {
                compared += replay(tag, s, problems);
            }
        }
        System.out.println("[b29] addon market golden steps compared: " + compared);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "addon-market-golden-problems.txt"), String.join("\n\n", problems), StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异，详见 target/addon-market-golden-problems.txt").isEmpty();
        assertThat(compared).isGreaterThanOrEqualTo(40);
        assertThat(leftoverTempFiles()).as("解包用的临时文件都已删除").isEqualTo(tmpBefore);
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        reset(SITE);
        String api = s.get("apiUrl").isNull() ? "" : s.get("apiUrl").asString();
        apiUrl = api.equals("{mock}") ? MOCK.uri("").toString().replaceAll("/$", "") : api;
        DOWNLOADS.clear();
        for (JsonNode d : s.get("download")) {
            DOWNLOADS.add(response(d));
        }
        Map<String, String> last = state(SITE);
        String sid = null;
        int n = 0;
        for (JsonNode st : s.get("steps")) {
            String step = label + "/" + st.get("label").asString();
            JsonNode req = st.get("request");
            int before = MOCK.requests().size();
            MockHttpServletResponse r = send(sid, req.get("method").asString(), req.get("path").asString(), req.get("query").asString(),
                    req.get("post").asString(), req.get("ajax").asBoolean());
            sid = cookie(r, sid);
            if (st.get("label").asString().equals("login")) {
                last = state(SITE);
                continue;
            }
            Map<String, String> now = state(SITE);
            Map<String, String> changed = diff(last, now);
            last = now;
            List<RecordedRequest> reqs = MOCK.requests().subList(before, MOCK.requests().size());
            String javaObs = observe(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8), changed, javaMock(reqs));
            String want = expected(step, st);
            if (!want.equals(javaObs)) {
                problems.add(tag + ":" + step + "\n" + AdminSystemGoldenTest.firstDiff(want, javaObs));
            }
            n++;
        }
        return n;
    }

    private static MockResponse response(JsonNode d) {
        int status = d.get("status").asInt();
        if (d.has("pkg")) {
            return new MockResponse(status, Map.of("Content-Type", "application/zip"),
                    Base64.getDecoder().decode(G80.get("packages").get(d.get("pkg").asString()).asString()), Duration.ZERO);
        }
        if (d.has("json")) {
            String json = JSON.writeValueAsString(d.get("json")).replace("{mock}", MOCK.uri("").toString().replaceAll("/$", ""));
            return MockResponse.json(status, json);
        }
        return MockResponse.text(status, d.get("body").asString());
    }

    // ------------------------------------------------------------------ 有意差异（35 文档第 10 节）

    private static final String INSTALL_URL = "\\/_admin.php\\/admin\\/addon\\/install.html";

    private static String err(String msg, String url) {
        return "status=200\nbody={\"code\":0,\"msg\":\"" + msg + "\",\"data\":\"\",\"url\":\"" + url + "\",\"wait\":3}\n";
    }

    private static String installErr(String msg) {
        return err(msg, INSTALL_URL);
    }

    /** 期望的新版结果：没有登记差异的步骤与旧版相同。 */
    static String expected(String step, JsonNode php) {
        String phpObs = observeGolden(php);
        String mock = phpMock(php);
        return switch (step) {
            // D-B29-1：生产现状里旧版没有 fast\Http（致命错误页）；新版未配置市场地址时不发请求，按"无法下载远程文件"
            case "prod-as-is/install" -> installErr("无法下载远程文件");
            // D-B29-2：旧版的 __() 未定义（致命错误页）；新版给出原意的提示
            case "adminloginbg/upgrade-enabled" -> err("Please disable addon first", "");
            // D-B29-3/4：旧版执行 install.sql 时 Db::getPdo() 为 false（致命错误页），资源与全局文件已复制、插件留在半装状态；新版装好插件目录、
            // 没有 Java 实现的插件保持禁用（info.ini 的 state 改 0、不复制资源与全局文件、钩子表不变），数据语句不执行（两边库都没有变化）
            case "third-party/install", "json-url/install" -> success("安装失败", thirdParty(php), mock);
            // D-B29-5：下载失败、坏包时旧版留下 runtime/addons/<名称>.zip；新版不落临时文件
            case "http-404/install", "bad-zip/install" -> installErr(AddonPackage.UNABLE_TO_OPEN) + mock;
            // D-B29-7：旧版（libzip）把 ../ 去掉后照样安装；新版整包拒绝
            case "traversal/install" -> installErr(AddonPackage.UNSAFE_PATH) + mock;
            // D-B29-8：旧版把 40 MiB 的 zip 炸弹整个解开并复制；新版按实际解压字节数拒绝
            case "zip-bomb/install" -> installErr(AddonPackage.TOO_LARGE) + mock;
            // D-B29-3：结构语句整包拒绝、不落盘（旧版同样因 Db::getPdo() 致命错误而没有建表，但插件文件留下）
            case "structural-sql/install" -> installErr(String.format(AddonMarketService.SQL_REFUSED, "CREATE")) + mock;
            // D-B29-9：冲突文件列表的路径分隔符（旧版在 Windows 上是 DIRECTORY_SEPARATOR）
            case "conflict/install" -> phpObs.replace("static\\\\js\\\\addons.js","static\\/js\\/addons.js");
            // D-B29-2：升级不存在的插件，旧版 RecursiveDirectoryIterator 抛异常（致命错误页）
            case "params/upgrade-missing" -> err("Addon not exists", "");
            default -> phpObs;
        };
    }

    private static String success(String msg, Map<String, String> files, String mock) {
        StringBuilder sb = new StringBuilder("status=200\nbody={\"code\":1,\"msg\":\"" + msg + "\",\"data\":\"\",\"url\":\"" + INSTALL_URL
                + "\",\"wait\":3}\n");
        sb.append(mock);
        files.forEach((k, v) -> sb.append("file ").append(k).append(" = ").append(v == null ? "<deleted>" : v).append('\n'));
        return sb.toString();
    }

    /** 第三方插件：只有插件目录；info.ini 按 set_addon_info 的写法改成 state = 0。 */
    private static Map<String, String> thirdParty(JsonNode php) {
        Map<String, String> files = new TreeMap<>();
        php.get("changes").properties().forEach(e -> {
            if (e.getKey().startsWith("addons/demo/") && !e.getKey().equals("addons/demo/info.ini")) {
                files.put(e.getKey(), e.getValue().asString());
            }
        });
        files.put("addons/demo/info.ini", sha256(("name = demo\ntitle = 测试插件demo\nintro = 插件市场黄金样本\nauthor = golden\nwebsite = \n"
                + "version = 1.0.0\nstate = 0\nurl = /addons/demo.html\n").getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    // ------------------------------------------------------------------ 观察

    static String observe(int status, String body, Map<String, String> changed, String mock) {
        StringBuilder sb = new StringBuilder("status=").append(status).append('\n');
        sb.append("body=").append(body.replaceAll("\"time\":\"?[0-9]+\"?", "\"time\":<t>")).append('\n');
        sb.append(mock);
        changed.forEach((k, v) -> sb.append("file ").append(k).append(" = ").append(v == null ? "<deleted>" : v).append('\n'));
        return sb.toString();
    }

    private static String observeGolden(JsonNode st) {
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        Map<String, String> changed = new TreeMap<>();
        st.get("changes").properties().forEach(e -> changed.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return observe(st.get("status").asInt(), body, changed, phpMock(st));
    }

    private static String phpMock(JsonNode st) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : st.get("mock")) {
            sb.append("mock ").append(m.get("method").asString()).append(' ').append(m.get("uri").asString()).append(" xrw=")
                    .append(m.get("xrw").isNull() ? "-" : m.get("xrw").asString()).append('\n');
        }
        return sb.toString();
    }

    private static String javaMock(List<RecordedRequest> reqs) {
        StringBuilder sb = new StringBuilder();
        for (RecordedRequest r : reqs) {
            String xrw = r.header("X-REQUESTED-WITH");
            sb.append("mock ").append(r.method()).append(' ').append(r.path()).append(r.query() == null || r.query().isEmpty() ? ""
                    : "?" + r.query()).append(" xrw=").append(xrw == null ? "-" : xrw).append('\n');
        }
        return sb.toString();
    }

    static Map<String, String> diff(Map<String, String> before, Map<String, String> after) {
        Map<String, String> d = new TreeMap<>();
        after.forEach((k, v) -> {
            if (!v.equals(before.get(k))) {
                d.put(k, v);
            }
        });
        before.keySet().forEach(k -> {
            if (!after.containsKey(k)) {
                d.put(k, null);
            }
        });
        return d;
    }

    private MockHttpServletResponse send(String sid, String method, String path, String query, String post, boolean ajax) throws Exception {
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(uri));
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded").content(post.getBytes(StandardCharsets.ISO_8859_1));
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

    private static long leftoverTempFiles() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("maccms-addon-")).count();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
