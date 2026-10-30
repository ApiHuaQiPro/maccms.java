package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.maccms.backend.admin.AdminForm;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.config.AdminConfiguration;
import com.maccms.backend.common.LegacyCheckout;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.content.application.ContentCacheMaintenance;
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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B15 后台系统配置、播放器/服务器组/下载器、站群、定时任务、快捷菜单、推送配置与本地插件的 PHP 黄金复放（不连库；24 文档第 10 节）。
 *
 * <p>黄金 {@code admin/system/php80-golden.json}、{@code php73-golden.json} 由 {@code src/test/php/admin_system_golden.php} 用真实的
 * 旧后台（ThinkPHP + 控制器，php-cgi）在临时站点副本上生成。这里按黄金里记下的初始文件重建一个临时站点（{@code static/player/} 取仓库文件并核对
 * SHA-256），每个场景开始前还原，再把同样的请求发给新版（表单步骤先 GET 新版自己的页面取令牌），逐步比较：状态码；JSON 与纯文本正文逐字节；
 * 页面按浏览器会提交的表单字段（{@link AdminForm}）；以及这一步之后变化的被跟踪文件逐字节相同。有意差异（24 文档第 13 节）在 {@link #JAVA} 等登记。
 * 数据库工具的场景要连库，在 {@code AdminSystemGoldenMySqlIT}。
 */
@WebMvcTest(controllers = AdminController.class, properties = {
        "maccms.admin.enabled=true",
        "maccms.admin.entry=/_admin.php",
        "maccms.admin.base-url=http://www.example.test/"})
@Import({AdminConfiguration.class, ClientIpConfiguration.class, AdminSystemGoldenTest.Fakes.class})
class AdminSystemGoldenTest {

    static final String E = "/_admin.php";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SITE;
    private static final JsonNode G80 = load("admin/system/php80-golden.json");
    private static final JsonNode G73 = load("admin/system/php73-golden.json");
    /** 本测试复放的场景（数据库工具在 MySQL IT）。 */
    static final Set<String> SKIP = Set.of("database", "database-plain");

    /** 有意差异：{@code 场景/步骤} → 新版的观察结果（{@link #observe}）。 */
    static final Map<String, String> JAVA = new TreeMap<>();
    /**
     * 表单页的有意差异：{@code 场景/步骤} → 旧版表单字段描述里要换成新版结果的行。D-B19-15：新建定时任务的执行文件下拉框没有
     * {@code collect}（旧版的第一项、也是默认选中项），新版默认是下一项 {@code make}。
     */
    static final Map<String, Map<String, String>> JAVA_FORM = Map.of("timming/save", Map.of("file=collect\n", "file=make\n"));
    /** PHP 8 整页 500、PHP 7.3 正常的步骤：两版都按 7.3 的结果比较（24 文档 D-B15-6）。 */
    static final Set<String> USE_73 = Set.of("config-search-rules-none/save");

    static {
        SITE = createSite();
        String ok = "status=200\nbody={\"code\":1,\"msg\":\"操作成功\",\"data\":\"\",\"url\":\"\\/_admin.php\\/admin\\/addon\\/state.html\",\"wait\":3}\n";
        // D-B15-11：没有 Java 实现的插件不能启用（旧版执行插件的 PHP 代码、复制资源与全局文件、登记钩子）；之后的禁用对一个本来就是禁用的插件
        // 只重写同样内容的 info.ini 与 addons.php，文件不变
        JAVA.put("addon/enable-demo", "status=200\nbody={\"code\":0,\"msg\":\"该插件没有新系统实现，无法启用\",\"data\":\"\",\"url\":\"\",\"wait\":3}\n");
        JAVA.put("addon/disable-demo", ok);
        // D-B15-14：可编辑的扩展名由 htm/html/js/xml 改为 jte/html/js/xml（htm 不再允许；提示文字随之改）
        String extTip = "status=200\nbody={\"code\":0,\"msg\":\"安全提示，后缀名只允许jte,html,js,xml\",\"data\":\"\",\"url\":\"\",\"wait\":3}\n";
        JAVA.put("template/bad-ext", extTip);
        JAVA.put("template/bad-ext-htm", extTip);
        // D-B15-15：标签向导改为按页面模板列出参数与 jte 写法，没有旧版生成标签的结果框 labels
        JAVA.put("template/wizard", observeGolden(stepOf(G80, "template", "wizard"), "80").replace("labels=\n", ""));
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeAdminTable fakeAdminTable() {
            FakeAdminTable t = new FakeAdminTable();
            t.put(1, "admin", LegacyInput.md5Hex("admin888".getBytes(StandardCharsets.UTF_8)), 1, "");
            t.put(2, "editor", LegacyInput.md5Hex("ed pass".getBytes(StandardCharsets.UTF_8)), 1,
                    ",system/config,vodplayer/index,");
            return t;
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

    static JsonNode golden(String tag) {
        return tag.equals("80") ? G80 : G73;
    }

    private static JsonNode load(String name) {
        try (InputStream in = AdminSystemGoldenTest.class.getResourceAsStream("/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 站点

    private static Path createSite() {
        try {
            Path site = Files.createTempDirectory("maccms-b15-site");
            site.toFile().deleteOnExit();
            reset(site, null);
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long mtime = 1_700_000_000L;

    /** 按黄金的初始文件重建跟踪区域，再写入场景的预置文件（base64，null 为删除）。 */
    static void reset(Path site, JsonNode scenarioFiles) throws IOException {
        for (String dir : List.of("application/extra", "application/data/backup", "static", "template", "addons")) {
            deleteTree(site.resolve(dir));
        }
        Files.createDirectories(site.resolve("application/data/backup"));
        Files.createDirectories(site.resolve("template"));
        Files.createDirectories(site.resolve("addons"));
        for (var e : G80.get("initialFiles").properties()) {
            String rel = e.getKey();
            String v = e.getValue().asString();
            Path p = site.resolve(rel);
            Files.createDirectories(p.getParent());
            if (v.startsWith("sha256:")) {
                // 写对上哈希的那份：后面逐字节比对的输出是按 CRLF 检出生成的
                byte[] repo = LegacyCheckout.read(Path.of("php").resolve(rel), v.substring(7));
                assertThat(sha256(repo)).as("仓库里的 " + rel + " 与生成黄金时不同" + LegacyCheckout.HINT).isEqualTo(v.substring(7));
                Files.write(p, repo);
            } else {
                Files.write(p, Base64.getDecoder().decode(v));
            }
            Files.setLastModifiedTime(p, FileTime.from(java.time.Instant.ofEpochSecond(mtime++)));
        }
        // 只列目录用到的（语言包、扩展类）取仓库的
        copyTree(Path.of("php", "application", "lang"), site.resolve("application/lang"));
        copyTree(Path.of("php", "application", "common", "extend"), site.resolve("application/common/extend"));
        if (scenarioFiles != null) {
            for (var e : scenarioFiles.properties()) {
                Path p = site.resolve(e.getKey());
                if (e.getValue().isNull()) {
                    Files.deleteIfExists(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.write(p, Base64.getDecoder().decode(e.getValue().asString()));
                    Files.setLastModifiedTime(p, FileTime.from(java.time.Instant.ofEpochSecond(mtime++)));
                }
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.copy(p, d);
                }
            }
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

    /** 被跟踪的文件（与黄金脚本的 tracked_files 相同的范围与归一）。 */
    static Map<String, byte[]> tracked(Path site) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(site.resolve("application/extra"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".php")).toList()) {
                out.put("application/extra/" + p.getFileName(), Files.readAllBytes(p));
            }
        }
        for (String f : List.of("application/route.php", "static/js/playerconfig.js", "static/js/tj.js", "static/js/addons.js")) {
            if (Files.isRegularFile(site.resolve(f))) {
                out.put(f, Files.readAllBytes(site.resolve(f)));
            }
        }
        for (String dir : List.of("static/player", "static/addons", "static/demo", "template", "addons", "application/data/backup")) {
            Path d = site.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String rel = dir + "/" + d.relativize(p).toString().replace('\\', '/');
                    byte[] c = Files.readAllBytes(p);
                    if (rel.startsWith("application/data/backup/")) {
                        rel = rel.replaceAll("\\d{8}-\\d{6}", "<time>");
                        if (rel.endsWith(".gz")) {
                            try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
                                c = in.readAllBytes();
                            }
                            rel += "(gunzip)";
                        }
                        c = new String(c, StandardCharsets.UTF_8).replaceAll("(?m)^-- Date : .*$", "-- Date : <time>")
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    out.put(rel, c);
                }
            }
        }
        return out;
    }

    static Map<String, String> diff(Map<String, byte[]> before, Map<String, byte[]> after) {
        Map<String, String> d = new TreeMap<>();
        after.forEach((k, v) -> {
            if (!before.containsKey(k) || !java.util.Arrays.equals(before.get(k), v)) {
                d.put(k, Base64.getEncoder().encodeToString(v));
            }
        });
        before.keySet().forEach(k -> {
            if (!after.containsKey(k)) {
                d.put(k, null);
            }
        });
        return d;
    }

    // ------------------------------------------------------------------ 复放

    @Test
    void scenariosMatchPhpGolden() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        String only = System.getProperty("golden.only", "");
        for (JsonNode g : List.of(G80, G73)) {
            String tag = g == G80 ? "80" : "73";
            for (JsonNode s : g.get("scenarios")) {
                String label = s.get("label").asString();
                if (SKIP.contains(label) || (!only.isEmpty() && !List.of(only.split(",")).contains(label))) {
                    continue;
                }
                compared += replay(tag, s, problems);
            }
        }
        System.out.println("[b15] admin system golden steps compared: " + compared);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-system-golden-problems.txt"), String.join("\n\n", problems), StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异，详见 target/admin-system-golden-problems.txt").isEmpty();
    }

    /**
     * D-B19-15（27 文档）：定时任务的执行文件 {@code collect}（定时连资源站采集）新系统不提供。新建任务的下拉框里没有这一项；已有的
     * {@code collect} 任务编辑页仍选中它（标明不提供，避免浏览器悄悄改成第一项），保存时固定拒绝、不写文件；其他执行文件照常保存。
     */
    @Test
    void timmingCollectTaskTypeIsNotProvided() throws Exception {
        JsonNode scenario = null;
        for (JsonNode s : G80.get("scenarios")) {
            if (s.get("label").asString().equals("timming")) {
                scenario = s;
            }
        }
        assertThat(scenario).isNotNull();
        reset(SITE, scenario.get("files"));
        JsonNode login = scenario.get("steps").get(0).get("request");
        String sid = cookie(send(null, "POST", login.get("path").asString(), login.get("query").asString(),
                login.get("post").asString(), login.get("ajax").asBoolean(), login.get("headers")), null);
        assertThat(sid).isNotNull();

        String fresh = send(sid, "GET", "/admin/timming/info", "", null, false, null).getContentAsString(StandardCharsets.UTF_8);
        org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(fresh);
        assertThat(d.select("select[name=file] option").eachAttr("value")).containsExactly("make", "cj", "cache", "urlsend");

        String existing = send(sid, "GET", "/admin/timming/info", "id=aa", null, false, null).getContentAsString(StandardCharsets.UTF_8);
        org.jsoup.nodes.Element chosen = org.jsoup.Jsoup.parse(existing).selectFirst("select[name=file] option[selected]");
        assertThat(chosen).isNotNull();
        assertThat(chosen.attr("value")).isEqualTo("collect");
        assertThat(chosen.text()).contains("新系统不提供");

        Map<String, byte[]> before = tracked(SITE);
        String token = token(existing);
        MockHttpServletResponse refused = send(sid, "POST", "/admin/timming/info", "", "__token__=" + token
                + "&status=0&name=aa&des=x&file=collect&param=ac%3Dcjday&weeks%5B%5D=1&hours%5B%5D=02", true, null);
        assertThat(refused.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":0,\"msg\":\""
                + com.maccms.backend.admin.application.ListConfigService.TIMMING_COLLECT_REFUSED
                + "\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        assertThat(diff(before, tracked(SITE))).isEmpty();

        String token2 = token(send(sid, "GET", "/admin/timming/info", "id=aa", null, false, null).getContentAsString(StandardCharsets.UTF_8));
        MockHttpServletResponse ok = send(sid, "POST", "/admin/timming/info", "", "__token__=" + token2
                + "&status=0&name=aa&des=x&file=make&param=ac%3Dindex&weeks%5B%5D=1&hours%5B%5D=02", true, null);
        assertThat(ok.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        assertThat(diff(before, tracked(SITE))).containsOnlyKeys("application/extra/timming.php");
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        reset(SITE, s.get("files"));
        Map<String, byte[]> last = tracked(SITE);
        String sid = null;
        int n = 0;
        for (JsonNode st : s.get("steps")) {
            String step = label + "/" + st.get("label").asString();
            JsonNode expected = USE_73.contains(step) ? stepOf(G73, label, st.get("label").asString()) : st;
            if (st.has("file")) {
                Path p = SITE.resolve(st.get("file").asString());
                if (st.get("content").isNull()) {
                    Files.deleteIfExists(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.write(p, Base64.getDecoder().decode(st.get("content").asString()));
                }
                last = tracked(SITE);
                continue;
            }
            JsonNode req = st.get("request");
            String javaPage = null;
            String token = null;
            if (st.has("page")) {
                MockHttpServletResponse page = send(sid, "GET", req.get("path").asString(), req.get("query").asString(), null, false, null);
                sid = cookie(page, sid);
                javaPage = page.getContentAsString(StandardCharsets.UTF_8);
                token = token(javaPage);
                String phpPage = new String(Base64.getDecoder().decode(st.get("page").get("body").asString()), StandardCharsets.UTF_8);
                String a = AdminForm.describe(AdminForm.fields(phpPage, 0));
                for (Map.Entry<String, String> e : JAVA_FORM.getOrDefault(step, Map.of()).entrySet()) {
                    a = a.replace(e.getKey(), e.getValue());
                }
                String b = AdminForm.describe(AdminForm.fields(javaPage, 0)).replaceAll("(?m)^(fversion|op)=.*\n", "");
                if (!a.equals(b)) {
                    problems.add(tag + ":" + step + " (表单页)\n" + firstDiff(a, b));
                }
            }
            MockHttpServletResponse r;
            if (st.has("upload")) {
                r = upload(sid, req, st.get("upload"), token);
            } else {
                String post = req.get("post").asString();
                r = send(sid, req.get("method").asString(), req.get("path").asString(),
                        req.get("query").asString().replace("{token}", token == null ? lastToken : token),
                        post.replace("{token}", token == null ? lastToken : token), req.get("ajax").asBoolean(), req.get("headers"));
            }
            sid = cookie(r, sid);
            String body = r.getContentAsString(StandardCharsets.UTF_8);
            String t = token(body);
            if (t != null) {
                lastToken = t;
            }
            if (token != null) {
                lastToken = token;
            }
            Map<String, byte[]> now = tracked(SITE);
            Map<String, String> changed = diff(last, now);
            last = now;
            String javaObs = observe(r.getStatus(), body, changed, tag);
            String phpObs = observeGolden(expected, tag);
            String want = JAVA.getOrDefault(tag + ":" + step, JAVA.getOrDefault(step, phpObs));
            if (!want.equals(javaObs)) {
                problems.add(tag + ":" + step + "\n" + firstDiff(want, javaObs));
            }
            n++;
        }
        return n;
    }

    private String lastToken = "";

    private static JsonNode stepOf(JsonNode g, String scenario, String step) {
        for (JsonNode s : g.get("scenarios")) {
            if (s.get("label").asString().equals(scenario)) {
                for (JsonNode st : s.get("steps")) {
                    if (st.get("label").asString().equals(step)) {
                        return st;
                    }
                }
            }
        }
        throw new IllegalArgumentException(scenario + "/" + step);
    }

    /** 观察结果：状态码、正文（JSON/文本原样；页面取表单字段与标题）、变化的文件（归一后）。 */
    static String observe(int status, String body, Map<String, String> changed, String tag) {
        StringBuilder sb = new StringBuilder("status=").append(status).append('\n');
        sb.append(bodyView(body));
        for (Map.Entry<String, String> e : changed.entrySet()) {
            sb.append("file ").append(e.getKey()).append(" = ")
                    .append(e.getValue() == null ? "<deleted>" : normalizeFile(e.getKey(),
                            new String(Base64.getDecoder().decode(e.getValue()), StandardCharsets.UTF_8)))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String observeGolden(JsonNode st, String tag) {
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        Map<String, String> changed = new TreeMap<>();
        st.get("changed").properties().forEach(e -> changed.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return observe(st.get("status").asInt(), body, changed, tag);
    }

    private static String bodyView(String body) {
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || trimmed.startsWith("<!doctype")) {
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(body);
            // 登录页背景（adminloginbg）：旧版写在 <style> 里，新版放在 login.js 的 data-background；随机背景的序号两边各自随机
            Matcher bg = Pattern.compile("background:url\\('([^']*)'\\)|data-background=\"([^\"]*)\"").matcher(body);
            String background = "";
            if (bg.find()) {
                background = (bg.group(1) != null ? bg.group(1) : org.jsoup.parser.Parser.unescapeEntities(bg.group(2), true))
                        .replaceAll("wallpaper/[0-9]+\\.jpg", "wallpaper/<n>.jpg");
            }
            // 模板编辑页新增的隐藏字段 fversion（乐观并发）与 op（恢复上一版），旧页面没有（24 文档第 7 节）
            String form = AdminForm.describe(AdminForm.fields(body, 0)).replaceAll("(?m)^(fversion|op)=.*\n", "");
            return "page title=" + d.title() + (background.isEmpty() ? "" : "\nbackground=" + background) + "\n"
                    + form + templateTable(d);
        }
        // 插件列表的 createtime 是插件目录的修改时间
        return "body=" + body.replaceAll("\"createtime\":[0-9]+", "\"createtime\":<t>") + "\n";
    }

    /**
     * 模板管理、广告位列表的表格（各行单元格文字；文件时间两边各自不同，归一）。新版表尾多一行"前台当前使用的模板"，不比较。
     */
    private static String templateTable(org.jsoup.nodes.Document d) {
        if (!d.title().startsWith("模板管理") && !d.title().startsWith("广告位管理")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Element tr : d.select("table.layui-table tr")) {
            String row = String.join(" | ", tr.select("th, td").eachText())
                    .replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", "<time>");
            if (!row.startsWith("前台当前使用")) {
                sb.append("row ").append(row).append('\n');
            }
        }
        return sb.toString();
    }

    /** 文件内容的归一：定时任务保存时旧版把表单令牌也写进了 timming.php（新旧令牌各自随机）；随机的 cache_flag。 */
    static String normalizeFile(String rel, String content) {
        String c = content;
        if (rel.equals("application/extra/timming.php")) {
            c = c.replaceAll("'__token__' => '[0-9a-f]{32}'", "'__token__' => '<token>'");
        }
        if (rel.equals("application/extra/maccms.php")) {
            c = c.replaceAll("'cache_flag' => '[0-9a-f]{10}'", "'cache_flag' => '<flag>'");
        }
        return c;
    }

    private static String token(String html) {
        Matcher m = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private MockHttpServletResponse send(String sid, String method, String path, String query, String post, boolean ajax,
                                         JsonNode headers) throws Exception {
        // 黄金里原样发给 php-cgi 的反斜杠（模板目录参数）在 URI 里写成 %5C，服务端解码后相同
        String uri = E + path + (query == null || query.isEmpty() ? "" : "?" + query.replace("\\", "%5C"));
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(uri));
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        if (headers != null) {
            headers.properties().forEach(e -> b.header(e.getKey(), e.getValue().asString()));
        }
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded").content(post.getBytes(StandardCharsets.ISO_8859_1));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse upload(String sid, JsonNode req, JsonNode up, String token) throws Exception {
        String query = req.get("query").asString().replace("{token}", lastToken);
        MockMultipartHttpServletRequestBuilder b = multipart(URI.create(E + req.get("path").asString()
                + (query.isEmpty() ? "" : "?" + query)));
        b.file(new MockMultipartFile(up.get("field").asString(), up.get("filename").asString(), "text/plain",
                Base64.getDecoder().decode(up.get("content").asString())));
        up.get("fields").properties().forEach(e -> b.param(e.getKey(), e.getValue().asString()));
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

    static String firstDiff(String want, String got) {
        String[] a = want.split("\n", -1);
        String[] b = got.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<none>";
            String y = i < b.length ? b[i] : "<none>";
            if (!x.equals(y)) {
                return "  line " + (i + 1) + "\n  want: " + abbreviate(x) + "\n  got:  " + abbreviate(y);
            }
        }
        return "  (same)";
    }

    private static String abbreviate(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "…(" + s.length() + ")" : s;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
