package com.maccms.backend.install;

import com.maccms.backend.install.config.InstallConfiguration.InstallConnectionsOverride;
import com.maccms.backend.install.infrastructure.InstallConnections;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B22 安装向导黄金样本的复放工具（31 文档第 9 节）：{@code install_golden.php} 在 PHP 7.3/8.0 上录下的请求，按同样的顺序发给新版。
 * <ul>
 * <li>JSON 响应：解析后逐字段相等（code/msg/data/url/wait）；</li>
 * <li>纯文本（安装锁提示）：逐字节相等；</li>
 * <li>HTML：去掉 {@code <script>}（旧版内联脚本移到 install-assets/，新版只有外链脚本）、{@code <meta>}（旧版的 {@code http-equiv}
 * 不再输出，31 文档第 7 节）、两个表单的 {@code __token__} 隐藏字段（旧版有一个但从不校验，新版两个表单都带并校验）、空白差异之后逐字相等；
 * 第二步的三张检测表 {@code <tbody>} 按设计不同（PHP 环境换成 Java 环境，31 文档第 3.2 节），只比较表头与其余结构。</li>
 * </ul>
 */
final class InstallGolden {

    static final String PHP80 = "install/b22/php80-golden.json";
    static final String PHP73 = "install/b22/php73-golden.json";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path REPO_APPLICATION = Path.of("php", "application");

    private InstallGolden() {
    }

    static JsonNode load(String resource) {
        try (InputStream in = InstallGolden.class.getClassLoader().getResourceAsStream(resource)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String body(JsonNode step) {
        return new String(Base64.getDecoder().decode(step.get("body").asString()), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 临时旧站与安装模式应用

    /** 一份临时旧站（只放新版读写的文件：extra/*.php、route.php 与检查的目录）与部署配置文件的位置（在旧站之外）。 */
    record Site(Path root, Path application, Path deployConfig) {

        static Site create() throws IOException {
            Path root = Files.createTempDirectory("maccms-b22-test");
            Path site = root.resolve("site");
            Path app = site.resolve("application");
            Files.createDirectories(app.resolve("extra"));
            try (Stream<Path> s = Files.list(REPO_APPLICATION.resolve("extra"))) {
                for (Path p : s.filter(p -> p.toString().endsWith(".php")).toList()) {
                    Files.copy(p, app.resolve("extra").resolve(p.getFileName()));
                }
            }
            Files.copy(REPO_APPLICATION.resolve("route.php"), app.resolve("route.php"));
            for (String d : List.of("data/backup", "data/update", "data/install")) {
                Files.createDirectories(app.resolve(d));
            }
            Files.createDirectories(site.resolve("upload"));
            Files.createDirectories(site.resolve("static"));
            Path deployDir = Files.createDirectories(root.resolve("deploy"));
            return new Site(root, app, deployDir.resolve("maccms-deploy.properties"));
        }

        Path lock() {
            return application.resolve("data/install/install.lock");
        }

        void delete() throws IOException {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** 以安装模式启动（随机端口）；{@code connections} 不为 null 时替换连接工厂（记录语句）。 */
    static ConfigurableApplicationContext start(Site site, boolean allowRoot, String adminEntry, InstallConnections connections,
                                                String... extra) {
        SpringApplicationBuilder b = InstallApplication.builder().web(WebApplicationType.SERVLET)
                .properties("MACCMS_SERVER_PORT=0", "MACCMS_SERVER_ADDRESS=127.0.0.1",
                        "MACCMS_LEGACY_CONFIG_DIR=" + site.application(),
                        "MACCMS_DEPLOY_CONFIG=" + site.deployConfig(),
                        "MACCMS_INSTALL_ALLOW_ROOT=" + allowRoot,
                        "MACCMS_ADMIN_ENTRY=" + (adminEntry == null ? "" : adminEntry))
                .properties(extra);
        if (connections != null) {
            b.initializers(ctx -> ctx.getBeanFactory().registerSingleton("installConnectionsOverride",
                    (InstallConnectionsOverride) () -> connections));
        }
        return b.run();
    }

    static MockMvc mvc(ConfigurableApplicationContext app) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
    }

    // ------------------------------------------------------------------ 请求与比较

    /** 发出一步请求：表单里的占位符换成 {@code vars}，需要时带上会话里的表单令牌（旧版没有这个字段）。 */
    static MockHttpServletResponse send(MockMvc mvc, MockHttpSession session, JsonNode request, Map<String, String> vars)
            throws Exception {
        String method = request.get("method").asString();
        String query = request.get("query").asString();
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),
                java.net.URI.create("/install.php" + (query.isEmpty() ? "" : "?" + query))).session(session);
        if (request.get("ajax").asBoolean()) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        JsonNode form = request.get("form");
        if (form != null && !form.isNull()) {
            if (session.getAttribute("install.token") == null) {
                // 旧版的表单没有令牌；新版的令牌在第三步页面里发给浏览器，复放时先打开一次第三步（不比较）
                mvc.perform(MockMvcRequestBuilders.get("/install.php?step=3").session(session));
            }
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : form.properties()) {
                String v = e.getValue().asString();
                for (Map.Entry<String, String> var : vars.entrySet()) {
                    v = v.replace(var.getKey(), var.getValue());
                }
                pairs.add(enc(e.getKey()) + "=" + enc(v));
            }
            Object token = session.getAttribute("install.token");
            if (token != null) {
                pairs.add("__token__=" + token);
            }
            b.contentType("application/x-www-form-urlencoded").content(String.join("&", pairs));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    /** 与黄金的一步比较；相同时为 null，否则为说明。 */
    static String compare(JsonNode golden, MockHttpServletResponse r) throws Exception {
        String want = body(golden);
        String got = r.getContentAsString(StandardCharsets.UTF_8);
        if (golden.get("status").asInt() != r.getStatus()) {
            return "状态码 " + golden.get("status").asInt() + " ≠ " + r.getStatus();
        }
        String ct = norm(golden.get("contentType").asString());
        if (!ct.equals(norm(r.getContentType()))) {
            return "Content-Type " + ct + " ≠ " + norm(r.getContentType());
        }
        if (ct.startsWith("application/json")) {
            JsonNode a = JSON.readTree(want);
            JsonNode b = JSON.readTree(got);
            return a.equals(b) ? null : "JSON " + want + " ≠ " + got;
        }
        if (!want.contains("<html") && !want.contains("<!DOCTYPE")) {
            return want.equals(got) ? null : "正文 " + want + " ≠ " + got;
        }
        boolean envTables = want.contains("lay-skin=\"line\"");
        String a = normalizeHtml(want, envTables);
        String b = normalizeHtml(got, envTables);
        if (a.equals(b)) {
            return null;
        }
        int i = 0;
        while (i < Math.min(a.length(), b.length()) && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 80);
        return "HTML 在第 " + i + " 字处不同：\n  旧版 …" + a.substring(from, Math.min(a.length(), i + 120))
                + "\n  新版 …" + b.substring(from, Math.min(b.length(), i + 120));
    }

    static String normalizeHtml(String html, boolean dropTableBodies) {
        String s = html.replaceAll("(?s)<script\\b.*?</script>", "")
                .replaceAll("<meta\\b[^>]*>", "")
                .replaceAll("<input type=\"hidden\" name=\"__token__\"[^>]*>", "");
        if (dropTableBodies) {
            s = s.replaceAll("(?s)<tbody>.*?</tbody>", "<tbody></tbody>");
        }
        return s.replaceAll("\\s+", " ").replaceAll("\\s*<", "<").replaceAll(">\\s*", ">").replaceAll("\\s+(/?>)", "$1").strip();
    }

    private static String norm(String contentType) {
        return contentType == null ? "" : contentType.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
