package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * site 的静态护栏（源码扫描）：
 * <ul>
 * <li>模板目录：正式模板只能放在 {@code src/main/jte/site/}，不得有其他模板目录或引擎；</li>
 * <li>模板写法：禁止不转义输出、禁止在事件属性/脚本/样式中放数据、禁止模板内 Java 代码块、禁止引用富文本字段；
 * URL 属性中的动态值只能是 {@code SafeUrl.href()}（URL 白名单，jte 默认转义不能代替协议校验）；
 * 只能 import site 的视图类型；表达式只能读取视图模型（不得用全限定名、静态 API、new、反射，
 * 因为 ModuleBoundaryTest 只扫描 .java）；</li>
 * <li>构建：jte 固定 3.2.4、预编译 + OwaspHtmlPolicy、不引入 Boot starter 或其他模板引擎；</li>
 * <li>装配：site 的每个 Spring 组件都带 {@code @ConditionalOnSiteEnabled}。</li>
 * </ul>
 * 默认转义不是富文本过滤：{@code vod_content}/{@code content} 必须经白名单过滤后由专门组件输出（后续任务）。
 */
class SiteGuardrailsTest {

    private static final Path JTE_ROOT = Path.of("src", "main", "jte");
    private static final Path SITE_TEMPLATES = JTE_ROOT.resolve("site");
    private static final Path SITE_JAVA = Path.of("src", "main", "java", "com", "maccms", "backend", "site");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\$unsafe\\{"),                                       // 不转义输出
            Pattern.compile("@raw\\b"),                                            // 原样输出块
            Pattern.compile("!\\{"),                                               // 模板内 Java 代码
            Pattern.compile("(?i)\\son[a-z]+\\s*=\\s*\"[^\"]*(\\$\\{|@)"),         // 事件属性中放数据
            Pattern.compile("(?is)<(script|style)\\b[^>]*>(?:(?!</\\1>).)*?(\\$\\{|@if|@for)"), // 脚本/样式块中放数据
            Pattern.compile("(?i)\\sstyle\\s*=\\s*\"[^\"]*\\$\\{"),                // 内联样式中放数据
            Pattern.compile("(?i)vod_content"),                                    // 原始富文本列
            Pattern.compile("\\.content\\(\\)"),                                   // 契约 VideoDetail.content
            Pattern.compile("@template\\.(?!site\\.)"));                           // 只能调用 site 下的模板

    /**
     * 模板表达式只能读视图模型：不得用全限定名绕过 @import 白名单（ModuleBoundaryTest 只扫描 .java），
     * 不得调用 System/Runtime/文件等静态 API、不得 new 对象、不得反射。在去掉 @import 行之后检查。
     */
    private static final List<Pattern> FORBIDDEN_CODE = List.of(
            Pattern.compile("\\b(?:java|javax|jakarta|org|com|gg|sun|jdk|kotlin)\\s*\\.\\s*[a-z]"),
            Pattern.compile("\\b(?:System|Runtime|ProcessBuilder|ProcessHandle|Class|ClassLoader|Thread|Files|Paths"
                    + "|Path|File|URI|URL)\\s*\\."),
            Pattern.compile("\\bnew\\s+[A-Za-z_]"),
            Pattern.compile("\\.getClass\\s*\\("));
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^@import\\s+\\S+\\s*$");

    /** 会被浏览器当作 URL 解释的属性。 */
    private static final Pattern URL_ATTRIBUTE = Pattern.compile(
            "(?i)\\s(href|src|action|formaction|poster|srcset|cite|data|background|manifest|ping|longdesc|codebase"
                    + "|xlink:href)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern SAFE_URL_VALUE = Pattern.compile("\\$\\{[\\w.()]+\\.href\\(\\)}");
    private static final Pattern IMPORT = Pattern.compile("(?m)^@import\\s+(\\S+)");
    private static final Pattern ALLOWED_IMPORT = Pattern.compile(
            "com\\.maccms\\.backend\\.site\\.(web\\.view|application)\\.\\w+|gg\\.jte\\.Content|java\\.util\\.\\w+");

    @Test
    void templatesLiveOnlyUnderSiteDirectory() throws IOException {
        List<Path> all = files(JTE_ROOT, ".jte");
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(p -> p.startsWith(SITE_TEMPLATES), "模板只能放在 src/main/jte/site/");
        // 不得有其他模板目录或其他引擎的模板（Thymeleaf/FreeMarker/Mustache 默认目录）
        assertThat(Path.of("src", "main", "resources", "templates")).doesNotExist();
        assertThat(files(Path.of("src", "main", "resources"), ".jte")).isEmpty();
        assertThat(files(Path.of("src", "main", "resources"), ".ftlh")).isEmpty();
        assertThat(files(Path.of("src", "main", "resources"), ".mustache")).isEmpty();
    }

    @Test
    void productionTemplatesFollowOutputRules() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path f : files(JTE_ROOT, ".jte")) {
            for (String v : violations(Files.readString(f, StandardCharsets.UTF_8))) {
                all.add(f + ": " + v);
            }
        }
        assertThat(all).isEmpty();
    }

    @Test
    void rulesRejectSyntheticViolations() {
        assertThat(violations("""
                @import com.maccms.backend.site.web.view.PageChrome
                @import gg.jte.Content
                @param PageChrome chrome
                <a class="x" href="${chrome.home().href()}" title="${chrome.siteName()}">${chrome.siteName()}</a>
                <link rel="canonical" href="${chrome.canonical().get().href()}">
                <a href="#main">跳转</a>
                @template.site.layout.page(chrome = chrome, content = @`<p>x</p>`)
                """)).isEmpty();

        for (String bad : List.of(
                "<p>$unsafe{x}</p>",
                "@raw<p>${x}</p>@endraw",
                "!{var u = \"/\" + x;}",
                "<button onclick=\"go('${x}')\">b</button>",
                "<a onmouseover=\"@if(a)x@endif\">b</a>",
                "<script>var s = \"${x}\";</script>",
                "<script type=\"text/plain\">\n  @if(a) 1 @endif\n</script>",
                "<style>p { color: ${x}; }</style>",
                "<p style=\"color: ${x}\">x</p>",
                "<a href=\"${x}\">x</a>",
                "<a href=\"/vod/${id}.html\">x</a>",
                "<img src=\"${card.pic()}\">",
                "<img srcset=\"${a.href()} 2x\">",
                "<form action=\"${x.url()}\"></form>",
                "<a href=\"${x.get(\"href\").href()}\">x</a>",
                "<div>${video.content()}</div>",
                "<div>vod_content</div>",
                "@template.layout.page(content = x)",
                "@import com.maccms.backend.content.application.view.VideoDetailDto",
                "@import com.maccms.backend.content.infrastructure.VodMapper",
                "@import java.nio.file.Files",
                "<p>${System.getenv(\"MACCMS_DB_PASSWORD\")}</p>",
                "<p>${java.nio.file.Files.readString(p)}</p>",
                "@param com.maccms.backend.content.infrastructure.VodDetailRow row",
                "<p>${com.maccms.backend.content.application.ContentReadService.class.getName()}</p>",
                "@if(Runtime.getRuntime() != null)x@endif",
                "<p>${new StringBuilder(x)}</p>",
                "<p>${x.getClass().getName()}</p>")) {
            assertThat(violations(bad)).as(bad).isNotEmpty();
        }
    }

    /** 返回一个模板文件违反的规则；空列表表示通过。 */
    static List<String> violations(String template) {
        List<String> out = new ArrayList<>();
        for (Pattern p : FORBIDDEN) {
            if (p.matcher(template).find()) {
                out.add("禁止的写法 " + p);
            }
        }
        String withoutImports = IMPORT_LINE.matcher(template).replaceAll("");
        for (Pattern p : FORBIDDEN_CODE) {
            if (p.matcher(withoutImports).find()) {
                out.add("模板表达式只能读取视图模型 " + p);
            }
        }
        Matcher url = URL_ATTRIBUTE.matcher(template);
        while (url.find()) {
            String value = url.group(2);
            if ((value.contains("${") || value.contains("@")) && !SAFE_URL_VALUE.matcher(value).matches()) {
                out.add("URL 属性 " + url.group(1) + " 的动态值必须整体是 SafeUrl.href()：" + value);
            }
        }
        Matcher imp = IMPORT.matcher(template);
        while (imp.find()) {
            if (!ALLOWED_IMPORT.matcher(imp.group(1)).matches()) {
                out.add("模板不得 import " + imp.group(1));
            }
        }
        return out;
    }

    /** B05（D-B05-1）：外层播放页唯一的同源 iframe 与播放器内页的 nonce 外部脚本，完整写法固定。 */
    private static final Pattern IFRAME = Pattern.compile("(?i)<iframe\\b[^>]*>");
    private static final Pattern IFRAME_ALLOWED = Pattern.compile("<iframe class=\"player-frame__iframe\" "
            + "src=\"\\$\\{page\\.playerUrl\\(\\)\\.href\\(\\)}\" title=\"\\$\\{page\\.playerTitle\\(\\)}\" allowfullscreen>");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b.*?</script\\s*>");
    private static final Pattern SCRIPT_ALLOWED = Pattern.compile(
            "<script nonce=\"\\$\\{page\\.nonce\\(\\)}\" src=\"\\$\\{[\\w.()]+\\.href\\(\\)}\"></script>");

    @Test
    void iframeOnlyOnThePlayPageAndScriptsOnlyAsNoncedExternalScriptsInThePlayerFrame() throws IOException {
        Path play = SITE_TEMPLATES.resolve(Path.of("pages", "play.jte"));
        Path player = SITE_TEMPLATES.resolve(Path.of("player", "player.jte"));
        // B09：会员页面共用的 member.js 只在这一个模板里输出
        Path member = SITE_TEMPLATES.resolve(Path.of("member", "script.jte"));
        int iframes = 0;
        int scripts = 0;
        for (Path f : files(JTE_ROOT, ".jte")) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher i = IFRAME.matcher(src);
            while (i.find()) {
                assertThat(f).as("只有外层播放页可以有 iframe").isEqualTo(play);
                assertThat(i.group()).as("iframe 的写法").matches(IFRAME_ALLOWED);
                iframes++;
            }
            Matcher sc = SCRIPT.matcher(src);
            while (sc.find()) {
                assertThat(f).as("只有播放器内页与会员页面的脚本模板可以有 script").isIn(player, member);
                assertThat(sc.group()).as("script 只能是带本响应 nonce 的同源外部脚本，没有内联内容").matches(SCRIPT_ALLOWED);
                scripts++;
            }
            assertThat(src.toLowerCase(java.util.Locale.ROOT).split("<script", -1).length - 1)
                    .as(f + " 中每个 <script 都已按完整写法检查").isEqualTo(f.equals(player) ? 2 : f.equals(member) ? 1 : 0);
        }
        assertThat(iframes).isEqualTo(1);
        assertThat(scripts).isEqualTo(3);
        for (String bad : List.of("<script>alert(1)</script>",
                "<script nonce=\"${page.nonce()}\">${page.data()}</script>",
                "<script nonce=\"x\" src=\"${s.href()}\"></script>",
                "<script nonce=\"${page.nonce()}\" src=\"/static/a.js\"></script>")) {
            assertThat(bad).as(bad).doesNotMatch(SCRIPT_ALLOWED);
        }
    }

    @Test
    void onlySafeUrlDeclaresHref() throws IOException {
        Pattern declares = Pattern.compile("record\\s+\\w+\\s*\\([^)]*\\bhref\\b|\\bhref\\s*\\(\\s*\\)\\s*\\{");
        List<String> declaring = new ArrayList<>();
        for (Path f : files(SITE_JAVA, ".java")) {
            if (declares.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                declaring.add(f.getFileName().toString());
            }
        }
        assertThat(declaring).as("模板里 .href() 只能来自 SafeUrl").containsExactly("SafeUrl.java");
    }

    @Test
    void everySiteSpringComponentIsConditionalOnSiteEnabled() throws IOException {
        Pattern component = Pattern.compile(
                "(?m)^@(Controller|RestController|ControllerAdvice|RestControllerAdvice|Configuration|Component|Service"
                        + "|Repository)\\b");
        List<Path> components = new ArrayList<>();
        for (Path f : files(SITE_JAVA, ".java")) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (component.matcher(src).find()) {
                components.add(f);
                assertThat(src).as(f + " 必须带 @ConditionalOnSiteEnabled").containsPattern("(?m)^@ConditionalOnSiteEnabled\\b");
            }
        }
        assertThat(components).extracting(p -> p.getFileName().toString())
                .contains("SiteConfiguration.java", "SitePlaceholderController.java", "SiteExceptionHandler.java",
                        "SiteContentController.java");
    }

    @Test
    void buildUsesPinnedPrecompiledJteWithoutStarter() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        assertThat(pom).contains("<jte.version>3.2.4</jte.version>");
        Matcher jte = Pattern.compile("(?s)<groupId>gg\\.jte</groupId>\\s*<artifactId>([\\w-]+)</artifactId>"
                + "\\s*<version>([^<]+)</version>").matcher(pom);
        List<String> artifacts = new ArrayList<>();
        while (jte.find()) {
            artifacts.add(jte.group(1));
            assertThat(jte.group(2)).as(jte.group(1)).isEqualTo("${jte.version}");
        }
        assertThat(artifacts).contains("jte-runtime", "jte-models", "jte-maven-plugin", "jte")
                .noneMatch(a -> a.contains("spring-boot-starter") || a.contains("kotlin"));
        assertThat(pom).doesNotContain("jte-spring-boot-starter")
                .doesNotContain("spring-boot-starter-thymeleaf")
                .doesNotContain("spring-boot-starter-freemarker")
                .doesNotContain("spring-boot-starter-mustache")
                .contains("<sourceDirectory>${project.basedir}/src/main/jte</sourceDirectory>")
                .contains("<contentType>Html</contentType>")
                .contains("<htmlPolicyClass>gg.jte.html.OwaspHtmlPolicy</htmlPolicyClass>")
                .contains("<className>gg.jte.models.generator.ModelExtension</className>");
        // B15（D4 模板在线编辑，24 文档第 7 节）起 jte 编译器进入运行时（编译作用域）；site 仍只用预编译的类，运行时编译只能出现在
        // sitetemplate.infrastructure（下面 runtimeTemplateCompilationOnlyInSiteTemplateInfrastructure）
        assertThat(pom).containsPattern("(?s)<artifactId>jte</artifactId>\\s*<version>\\$\\{jte.version}</version>\\s*</dependency>");
    }

    @Test
    void runtimeTemplateCompilationOnlyInSiteTemplateInfrastructure() throws IOException {
        Pattern compile = Pattern.compile("TemplateEngine\\s*\\.\\s*create\\s*\\(|DirectoryCodeResolver|ResourceCodeResolver"
                + "|gg\\.jte\\.compiler|precompileAll|javax\\.tools");
        List<String> hits = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        for (Path p : files(root, ".java")) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (compile.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()
                    && !rel.startsWith("com/maccms/backend/sitetemplate/infrastructure/")) {
                hits.add(rel);
            }
        }
        assertThat(hits).isEmpty();
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(suffix)).toList();
        }
    }
}
