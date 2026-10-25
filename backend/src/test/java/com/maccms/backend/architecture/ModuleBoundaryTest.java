package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 依赖边界护栏（纯源码扫描，见 docs/architecture/02-agent-development-rules.md 第 2 节）：
 * <ul>
 * <li>模块内 api → application → infrastructure 自上而下；application、infrastructure 不得引用本模块 api。</li>
 * <li>跨模块只能引用对方 {@code application} 的公开服务与 {@code application.view} 已发布只读类型。</li>
 * <li>MyBatis/JDBC 类型只出现在 infrastructure（以及 common.config 的数据源检查、common.write 的可写数据源（B07））。</li>
 * <li>site 只能引用 content.application(.view)、member.application(.view)（B09）、common 中登记的支持类与 legacyconfig 中登记的读取类型，不得接触任何数据访问类型，
 * 不得有自己的 Mapper XML；也不得读文件、环境变量，启动进程或发起网络请求（站点设置来自 {@code maccms.site.*} 外部化配置，
 * 留空的项经 {@code LegacyConfigService} 取旧配置（B02），site 自己不读文件；不回环调用旧 PHP 或本应用 API）。
 * 规则由 {@link #siteRulesOnSyntheticSources()} 自检，并由 {@link #mainSourcesRespectModuleBoundaries()} 覆盖真实文件。</li>
 * </ul>
 * 注释与字符串字面量中的包名不计入。
 */
class ModuleBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path MAPPER_XML = Path.of("src", "main", "resources", "mapper");
    private static final String ROOT = "com.maccms.backend";

    /**
     * site 可用的 common 类型；扩充须在任务中说明并同步 02 规范。
     * B02（MACC-26）加入浏览器侧外部地址放行开关及其装配类（site 装配时引入，不读环境变量）。
     * B06（MACC-30）加入 {@code LegacyJson}（旧 ajax/* 接口与 Provide 共用的 PHP json_encode 等价编码，纯计算）与
     * {@code IpAddress}（IP 字面量解析，二维码 URL 校验的 IPv6 主机；不做 DNS 查询）。
     * B08（MACC-32）加入进程内会话 {@code SessionStore}/{@code SessionState}、本地验证码 {@code Captchas}/{@code CaptchaSettings}
     * 与 PHP 兼容的 cookie 读写 {@code PhpCookies}（均为纯内存计算，不读文件、不发网络请求）。
     * B09（MACC-33）加入按字节解析查询串与表单的 {@code PhpInput}（纯计算）与客户端 IP 的唯一来源 {@code ClientIpResolver}
     * （登录 IP、注册 IP 限额，D2 ④；只读请求对端与已登记的转发头）。
     */
    private static final Set<String> SITE_COMMON = Set.of(
            "common.web.PageResponse", "common.web.ApiException", "common.web.ErrorCode", "common.web.SafeExceptionLog",
            "common.browser.BrowserExternalAccess", "common.browser.BrowserExternalConfiguration",
            "common.web.LegacyJson", "common.clientip.IpAddress",
            "common.session.SessionStore", "common.session.SessionState", "common.captcha.Captchas",
            "common.captcha.CaptchaSettings", "common.web.PhpCookies", "common.web.PhpInput",
            "common.clientip.ClientIpResolver");

    /**
     * site 可用的 legacyconfig 类型（B02，MACC-26）：经 {@code LegacyConfigService} 读取 {@code extra/maccms.php} 并按字段并入站点设置。
     * 不含 {@code LegacyConfigProperties}（目录只由部署参数决定）与 infrastructure（site 不自己读文件）。扩充须同步 02 规范。
     */
    private static final Set<String> SITE_LEGACYCONFIG = Set.of(
            "legacyconfig.application.LegacyConfigService", "legacyconfig.application.LegacyConfigFile",
            "legacyconfig.application.LegacyConfigException", "legacyconfig.application.LegacyConfigConfiguration",
            "legacyconfig.application.view.PhpArray", "legacyconfig.application.view.PhpValue",
            "legacyconfig.application.view.PhpValues");

    /**
     * infrastructure 之外允许出现数据访问类型的包（精确匹配，不含子包）：common.config 的数据源账号检查；common.write 的
     * 可写数据源、写入护栏与写入序列（B07，MACC-31，docs/architecture/12-write-infrastructure.md 第 2 节）。扩充须同步 02 规范。
     */
    private static final Set<String> DATA_ACCESS_OUTSIDE_INFRASTRUCTURE = Set.of("common.config", "common.write");

    /** 已存在、暂不调整的向上依赖：VodMapper 用排序枚举生成 ORDER BY 白名单（MACC-4）。不得新增。 */
    private static final Set<String> KNOWN_UPWARD = Set.of(
            "content.infrastructure.VodMapper -> content.application.VideoSort");

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern BACKEND_REF = Pattern.compile(
            "\\bcom\\.maccms\\.backend\\.([a-z_]\\w*(?:\\.[a-z_]\\w*)*)(?:\\.([A-Z]\\w*))?");
    private static final Pattern MYBATIS_JDBC = Pattern.compile(
            "\\b(org\\.apache\\.ibatis|org\\.mybatis|java\\.sql|javax\\.sql|org\\.springframework\\.jdbc|"
                    + "com\\.zaxxer\\.hikari|com\\.mysql)\\.");
    private static final Pattern SITE_FORBIDDEN_EXTRA = Pattern.compile("\\b(org\\.springframework\\.dao)\\.");
    /**
     * site 不读文件/环境变量、不启动进程、不发起网络请求（MACC-9）。java.net.URI 允许（URL 白名单校验要用）。
     * jsoup 只能用解析/节点/过滤等包（MACC-12 富文本过滤），禁止带网络功能的 Jsoup、Connection 与通配导入。
     */
    private static final Pattern SITE_FORBIDDEN_IO = Pattern.compile(
            "\\b(java\\.nio\\.file\\.|java\\.io\\.File\\w*\\b|java\\.io\\.RandomAccessFile\\b|java\\.net\\.http\\."
                    + "|org\\.jsoup\\.(?:Jsoup|Connection|helper\\.HttpConnection)\\b|org\\.jsoup\\.(?:helper\\.)?\\*"
                    + "|java\\.net\\.(?:URL|URLConnection|HttpURLConnection|Socket|ServerSocket)\\b"
                    + "|org\\.springframework\\.web\\.client\\.|org\\.springframework\\.web\\.reactive\\."
                    + "|org\\.springframework\\.core\\.io\\.|ProcessBuilder\\b|Runtime\\s*\\.\\s*getRuntime\\b"
                    + "|System\\s*\\.\\s*getenv\\b|System\\s*\\.\\s*getProperty\\b)");

    @Test
    void mainSourcesRespectModuleBoundaries() throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String file = MAIN_JAVA.relativize(p).toString().replace('\\', '/');
                for (String v : violations(Files.readString(p, StandardCharsets.UTF_8))) {
                    all.add(file + ": " + v);
                }
            }
        }
        assertThat(all).as("依赖边界").isEmpty();
    }

    @Test
    void contentApplicationNoLongerUsesContentApi() throws IOException {
        Path app = MAIN_JAVA.resolve(Path.of("com", "maccms", "backend", "content", "application"));
        try (Stream<Path> s = Files.walk(app)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(code(Files.readString(p, StandardCharsets.UTF_8))).as(p.toString())
                        .doesNotContain("com.maccms.backend.content.api");
            }
        }
        assertThat(app.resolve("view")).isDirectory();
        assertThat(MAIN_JAVA.resolve(Path.of("com", "maccms", "backend", "content", "api", "dto"))).doesNotExist();
    }

    /**
     * TwelveMonkeys（B09 WebP 头像，18 文档第 14 节）只由 {@code member.application.PortraitImages} 引用，且只引用 WebP 读取器的 SPI；
     * 其余类型（含 common-io 的 {@code com.twelvemonkeys.net}）一律不用。扩充须在任务中说明。
     */
    @Test
    void twelveMonkeysIsOnlyUsedForThePortraitWebPReader() throws IOException {
        Pattern ref = Pattern.compile("\\bcom\\.twelvemonkeys\\.[\\w.*]+");
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String file = MAIN_JAVA.relativize(p).toString().replace('\\', '/');
                Matcher m = ref.matcher(code(Files.readString(p, StandardCharsets.UTF_8)));
                while (m.find()) {
                    found.add(file + ": " + m.group());
                }
            }
        }
        assertThat(found).containsExactly("com/maccms/backend/member/application/PortraitImages.java: "
                + "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
    }

    @Test
    void siteHasNoMapperXml() {
        assertThat(MAPPER_XML.resolve("site")).doesNotExist();
    }

    @Test
    void siteRulesOnSyntheticSources() {
        assertThat(violations(site("""
                import com.maccms.backend.content.application.ContentReadService;
                import com.maccms.backend.content.application.VideoListQuery;
                import com.maccms.backend.content.application.view.VideoDetailDto;
                import com.maccms.backend.common.web.ApiException;
                import com.maccms.backend.common.web.ErrorCode;
                import com.maccms.backend.common.web.PageResponse;
                import com.maccms.backend.common.web.SafeExceptionLog;
                import com.maccms.backend.site.application.UrlBuilder;
                import com.maccms.backend.legacyconfig.application.LegacyConfigService;
                import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
                import com.maccms.backend.legacyconfig.application.view.PhpArray;
                import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
                import java.net.URI;
                import java.io.Serializable;
                import org.jsoup.nodes.Document;
                import org.jsoup.parser.Parser;
                import org.jsoup.safety.Cleaner;
                // 注释里提到 com.maccms.backend.content.infrastructure.VodMapper 不算依赖
                class Ok { String s = "com.maccms.backend.content.api.VideoController java.sql.Connection"; }
                """))).isEmpty();

        for (String bad : List.of(
                "import com.maccms.backend.content.api.VideoController;",
                "import com.maccms.backend.content.api.*;",
                "import com.maccms.backend.content.infrastructure.VodMapper;",
                "import com.maccms.backend.content.infrastructure.VodDetailRow;",
                "import com.maccms.backend.common.web.GlobalExceptionHandler;",
                "import com.maccms.backend.common.web.ErrorResponse;",
                "import com.maccms.backend.common.config.DatabaseAccountGuard;",
                "import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;",
                "import com.maccms.backend.legacyconfig.application.VarExportParser;",
                "import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory;",
                "import com.maccms.backend.legacyconfig.application.*;",
                "import org.apache.ibatis.annotations.Mapper;",
                "import org.mybatis.spring.annotation.MapperScan;",
                "import java.sql.Connection;",
                "import javax.sql.DataSource;",
                "import org.springframework.jdbc.core.JdbcTemplate;",
                "import org.springframework.dao.DataAccessException;",
                "import com.zaxxer.hikari.HikariDataSource;",
                "class X { Object o = new com.maccms.backend.content.infrastructure.TypeRow(); }",
                "import java.nio.file.Files;",
                "import java.nio.file.*;",
                "import java.io.File;",
                "import java.io.FileInputStream;",
                "import java.net.http.HttpClient;",
                "import java.net.URL;",
                "import java.net.HttpURLConnection;",
                "import org.jsoup.Jsoup;",
                "import org.jsoup.Connection;",
                "import org.jsoup.*;",
                "import static org.jsoup.Jsoup.connect;",
                "import org.jsoup.helper.HttpConnection;",
                "class X { Object o = org.jsoup.Jsoup.connect(\"http://x.test\"); }",
                "import org.springframework.web.client.RestClient;",
                "import org.springframework.web.reactive.function.client.WebClient;",
                "import org.springframework.core.io.ClassPathResource;",
                "class X { void f() throws Exception { new ProcessBuilder(\"php\").start(); } }",
                "class X { void f() throws Exception { Runtime.getRuntime().exec(\"php\"); } }",
                "class X { String s = System.getenv(\"MACCMS_SITE_NAME\"); }",
                "class X { String s = System.getProperty(\"user.dir\"); }")) {
            assertThat(violations(site(bad))).as(bad).isNotEmpty();
        }
        // site 内部也不能建数据访问层
        assertThat(violations("package com.maccms.backend.site.infrastructure;\nimport org.apache.ibatis.annotations.Mapper;"))
                .isNotEmpty();
    }

    @Test
    void layeringRulesOnSyntheticSources() {
        assertThat(violations("package com.maccms.backend.content.application;\n"
                + "import com.maccms.backend.content.api.dto.CategoryDto;")).isNotEmpty();
        assertThat(violations("package com.maccms.backend.content.application.view;\n"
                + "class X { com.maccms.backend.content.api.VideoController c; }")).isNotEmpty();
        assertThat(violations("package com.maccms.backend.content.infrastructure;\n"
                + "import com.maccms.backend.content.application.ContentReadService;")).isNotEmpty();
        assertThat(violations("package com.maccms.backend.common.web;\n"
                + "import com.maccms.backend.content.application.ContentReadService;")).isNotEmpty();
        assertThat(violations("package com.maccms.backend.content.application;\n"
                + "import java.sql.ResultSet;")).isNotEmpty();
        // 数据访问类型的例外只有 common.config、common.write 两个包本身（B07）
        assertThat(violations("package com.maccms.backend.common.write;\n"
                + "import java.sql.SQLException;\nimport org.apache.ibatis.plugin.Interceptor;\n"
                + "import com.zaxxer.hikari.HikariDataSource;")).isEmpty();
        for (String pkg : List.of("common.web", "common.writex", "common.write.internal", "common", "member.application")) {
            assertThat(violations("package com.maccms.backend." + pkg + ";\nimport java.sql.SQLException;"))
                    .as(pkg).isNotEmpty();
        }
        // common.write 也不能引用业务模块
        assertThat(violations("package com.maccms.backend.common.write;\n"
                + "import com.maccms.backend.content.application.ContentReadService;")).isNotEmpty();
        // 另一业务模块只能用 content.application 与 .view
        assertThat(violations("package com.maccms.backend.member.application;\n"
                + "import com.maccms.backend.content.application.ContentReadService;\n"
                + "import com.maccms.backend.content.application.view.CategoryRefDto;")).isEmpty();
        assertThat(violations("package com.maccms.backend.member.application;\n"
                + "import com.maccms.backend.content.application.internal.Helper;")).isNotEmpty();
        assertThat(violations("package com.maccms.backend.member.api;\n"
                + "import com.maccms.backend.content.api.VideoController;")).isNotEmpty();
        // 正向依赖允许
        assertThat(violations("package com.maccms.backend.content.api;\n"
                + "import com.maccms.backend.content.application.ContentReadService;\n"
                + "import com.maccms.backend.content.application.view.VideoDetailDto;")).isEmpty();
        assertThat(violations("package com.maccms.backend.content.application;\n"
                + "import com.maccms.backend.content.infrastructure.VodMapper;")).isEmpty();
    }

    private static String site(String body) {
        return "package com.maccms.backend.site.web;\n" + body;
    }

    /** 返回一个源文件违反的边界规则；空列表表示通过。 */
    static List<String> violations(String source) {
        String code = code(source);
        Matcher pm = PACKAGE.matcher(code);
        if (!pm.find() || !(pm.group(1) + ".").startsWith(ROOT + ".")) {
            return List.of();
        }
        String pkg = pm.group(1).equals(ROOT) ? "" : pm.group(1).substring(ROOT.length() + 1);
        String module = segment(pkg, 0);
        String layer = segment(pkg, 1);
        String self = pkg + "." + className(code);
        List<String> out = new ArrayList<>();

        Matcher m = BACKEND_REF.matcher(code);
        while (m.find()) {
            String refPkg = m.group(1);
            String ref = m.group(2) == null ? refPkg + ".*" : refPkg + "." + m.group(2);
            String refModule = segment(refPkg, 0);
            String refLayer = segment(refPkg, 1);
            if (refPkg.equals(pkg) || module.isEmpty()) {
                continue;
            }
            if ("site".equals(module)) {
                boolean allowed = "site".equals(refModule)
                        || refPkg.equals("content.application") || refPkg.equals("content.application.view")
                        || refPkg.equals("member.application") || refPkg.equals("member.application.view")
                        || refPkg.equals("comment.application") || refPkg.equals("comment.application.view")
                        || SITE_COMMON.contains(ref) || SITE_LEGACYCONFIG.contains(ref);
                if (!allowed) {
                    out.add("site 不得引用 " + ref);
                }
                continue;
            }
            if ("common".equals(module)) {
                if (!"common".equals(refModule)) {
                    out.add("common 不得引用业务模块 " + ref);
                }
                continue;
            }
            if (refModule.equals(module)) {
                if (!"api".equals(layer) && "api".equals(refLayer)) {
                    out.add(layer + " 不得引用本模块 api：" + ref);
                } else if ("infrastructure".equals(layer) && "application".equals(refLayer)
                        && !KNOWN_UPWARD.contains(self + " -> " + ref)) {
                    out.add("infrastructure 不得引用本模块 application：" + ref);
                }
            } else if (!"common".equals(refModule)
                    && !refPkg.equals(refModule + ".application") && !refPkg.equals(refModule + ".application.view")) {
                out.add("跨模块只能引用 " + refModule + ".application 与 .application.view：" + ref);
            }
        }

        Matcher db = MYBATIS_JDBC.matcher(code);
        while (db.find()) {
            boolean allowed = !"site".equals(module)
                    && ("infrastructure".equals(layer) || DATA_ACCESS_OUTSIDE_INFRASTRUCTURE.contains(pkg));
            if (!allowed) {
                out.add("数据访问类型只能出现在 infrastructure：" + db.group(1));
            }
        }
        if ("site".equals(module)) {
            Matcher dao = SITE_FORBIDDEN_EXTRA.matcher(code);
            while (dao.find()) {
                out.add("site 不得引用数据访问异常类型：" + dao.group(1));
            }
            Matcher io = SITE_FORBIDDEN_IO.matcher(code);
            while (io.find()) {
                out.add("site 不得读文件/环境变量、启动进程或发起网络请求：" + io.group(1));
            }
        }
        return out;
    }

    private static String segment(String pkg, int i) {
        String[] parts = pkg.split("\\.");
        return i < parts.length ? parts[i] : "";
    }

    private static String className(String code) {
        Matcher m = Pattern.compile("\\b(?:class|record|interface|enum)\\s+(\\w+)").matcher(code);
        return m.find() ? m.group(1) : "?";
    }

    /** 去掉注释与字符串/字符字面量（含文本块），只留代码。 */
    static String code(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                sb.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                sb.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                sb.append(c).append(c);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
