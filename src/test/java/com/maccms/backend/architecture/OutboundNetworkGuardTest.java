package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.integration.application.IntegrationPackages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 出站护栏（B02 建设，B23 起按子包登记；路线图第 1.3、4.3 节 ④；docs/architecture/11-guards-and-legacy-config.md 第 2 节、
 * 26-b23-integration-foundation.md 第 2 节）：扫描 src/main/java，登记表取自主代码 {@link IntegrationPackages}（运行期的调用方检查用同一份）。
 * <ul>
 * <li>出站网络 API（JDK HTTP/URL/套接字、JavaMail、Spring 各类客户端、FTP、jsoup 联网等）只允许出现在 {@link IntegrationPackages#RAW_API}
 * 登记的子包；B23 只有出站客户端所在的 {@code integration.http}。已登记的业务子包（{@code integration.pay} 等）也不能直接用，
 * 必须经出站客户端。</li>
 * <li>出站客户端（{@code com.maccms.backend.integration.http}）只允许被 {@link IntegrationPackages#REGISTERED} 登记的子包引用。
 * B23 交付时登记表为空；B24～B29 每批只登记本批的子包，登记改动必须出现在该批的合并提交中。E01/E02/E03/E08 的本地版本、采集链、
 * 上传与图片处理永远不登记；E05 支付回调、E12 公众号是入站接口，不需要登记。</li>
 * </ul>
 *
 * <p>扫描的是去掉注释与字符串后的代码，覆盖导入、全限定名与通配导入；另外按方法名拦截不需要导入就能调用的
 * {@code .openConnection(}、{@code .openStream(}、{@code .toURL(}。同时检查 pom.xml 不引入出站客户端依赖。
 * 规则由 {@link #rulesOnSyntheticSources()} 自检。
 */
class OutboundNetworkGuardTest {

    /** 可以经出站客户端发请求的子包（相对 com.maccms.backend）→ 登记批次。 */
    static final Map<String, String> REGISTERED = IntegrationPackages.REGISTERED;

    /** 可以直接使用出站 API 的子包 → 登记批次。 */
    static final Map<String, String> RAW_API = IntegrationPackages.RAW_API;

    private static final Pattern CLIENT_REF = Pattern.compile("\\bcom\\.maccms\\.backend\\.integration\\.http\\b");

    private static final Pattern OUTBOUND_TYPES = Pattern.compile("\\b("
            + "java\\.net\\.http\\."
            + "|java\\.net\\.(?:URL|URLConnection|HttpURLConnection|JarURLConnection|URLClassLoader|Socket|SocketImpl"
            + "|InetAddress|Inet4Address|Inet6Address|InetSocketAddress|DatagramSocket|MulticastSocket|ServerSocket"
            + "|Proxy|ProxySelector|Authenticator|CookieHandler|CookieManager)\\b"
            + "|java\\.net\\.\\*"
            + "|java\\.nio\\.channels\\.(?:SocketChannel|DatagramChannel|AsynchronousSocketChannel|ServerSocketChannel"
            + "|NetworkChannel)\\b|java\\.nio\\.channels\\.\\*"
            + "|javax\\.net\\.|java\\.rmi\\.|javax\\.naming\\.|javax\\.management\\.remote\\."
            + "|jakarta\\.mail\\.|javax\\.mail\\.|org\\.eclipse\\.angus\\.mail\\.|org\\.springframework\\.mail\\."
            + "|org\\.springframework\\.web\\.client\\.|org\\.springframework\\.web\\.reactive\\.function\\.client\\."
            + "|org\\.springframework\\.http\\.client\\.|org\\.springframework\\.web\\.service\\.invoker\\."
            + "|org\\.springframework\\.web\\.socket\\.client\\.|org\\.springframework\\.cloud\\.openfeign\\."
            + "|org\\.springframework\\.core\\.io\\.(?:UrlResource|FileUrlResource)\\b"
            + "|org\\.springframework\\.util\\.ResourceUtils\\b"
            + "|org\\.apache\\.commons\\.net\\.|org\\.apache\\.hc\\.|org\\.apache\\.http\\.|okhttp3\\.|retrofit2\\."
            + "|io\\.netty\\.|reactor\\.netty\\.|org\\.eclipse\\.jetty\\.client\\.|com\\.jcraft\\.jsch\\."
            + "|com\\.sun\\.net\\.|sun\\.net\\."
            + "|org\\.jsoup\\.(?:Jsoup|Connection)\\b|org\\.jsoup\\.helper\\.HttpConnection\\b"
            + "|org\\.jsoup\\.(?:helper\\.)?\\*"
            + ")");

    private static final Pattern OUTBOUND_CALLS = Pattern.compile(
            "\\.\\s*(openConnection|openStream|toURL)\\s*\\(");

    /** pom.xml 中不允许出现的出站客户端依赖（artifactId）。B23 起按批登记时同步调整。 */
    private static final List<String> FORBIDDEN_ARTIFACTS = List.of(
            "spring-boot-starter-mail", "spring-boot-starter-webflux", "spring-boot-starter-webclient",
            "spring-boot-starter-restclient", "spring-boot-starter-reactor-netty", "spring-boot-starter-websocket",
            "spring-cloud-starter-openfeign", "commons-net", "okhttp", "httpclient5", "httpclient", "httpasyncclient",
            "jakarta.mail", "jakarta.mail-api", "angus-mail", "javax.mail", "jsch", "netty-all", "reactor-netty-http");

    @Test
    void registeredPackagesAreIntegrationSubpackages() {
        assertThat(REGISTERED.keySet()).allSatisfy(p -> assertThat(p).matches("integration\\.[a-z][a-z0-9]*")
                .isNotIn(IntegrationPackages.CLIENT, "integration.application", "integration.testing"));
        assertThat(RAW_API.keySet()).allSatisfy(p -> assertThat(p).matches("integration\\.[a-z][a-z0-9]*"));
        assertThat(RAW_API).containsEntry(IntegrationPackages.CLIENT, "B23");
        assertThat(REGISTERED.values()).allSatisfy(b -> assertThat(b).matches("B2[4-9]"));
    }

    @Test
    void mainSourcesUseOutboundApisOnlyInRegisteredPackages() throws IOException {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : JavaSources.mainJava().entrySet()) {
            for (String v : violations(e.getValue(), REGISTERED, RAW_API)) {
                all.add(e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("出站护栏：出站网络 API 只能出现在 integration.http，出站客户端只能由已登记的 integration 子包使用").isEmpty();
    }

    @Test
    void pomHasNoOutboundClientDependencies() throws IOException {
        String pom = JavaSources.stripXmlComments(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
        Matcher m = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>").matcher(pom);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (FORBIDDEN_ARTIFACTS.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        assertThat(found).as("pom.xml 引入了出站客户端依赖").isEmpty();
    }

    @Test
    void rulesOnSyntheticSources() {
        String ok = """
                package com.maccms.backend.content.application;
                import java.net.URI;
                import java.net.URLEncoder;
                import java.net.URLDecoder;
                import java.nio.file.Files;
                import org.jsoup.parser.Parser;
                import org.jsoup.nodes.Document;
                // 注释里写 java.net.http.HttpClient、new java.net.URL(x).openConnection() 不算
                class Ok { String s = "java.net.Socket org.springframework.web.client.RestClient .openStream("; }
                """;
        assertThat(violations(ok, Map.of(), RAW)).isEmpty();

        for (String bad : List.of(
                "import java.net.http.HttpClient;",
                "import java.net.http.*;",
                "import java.net.URL;",
                "import java.net.URLConnection;",
                "import java.net.HttpURLConnection;",
                "import java.net.Socket;",
                "import java.net.InetAddress;",
                "import java.net.InetSocketAddress;",
                "import java.net.DatagramSocket;",
                "import java.net.*;",
                "import java.nio.channels.SocketChannel;",
                "import javax.net.ssl.SSLSocketFactory;",
                "import javax.naming.InitialContext;",
                "import java.rmi.Naming;",
                "import jakarta.mail.Transport;",
                "import org.springframework.mail.javamail.JavaMailSender;",
                "import org.springframework.web.client.RestClient;",
                "import org.springframework.web.client.RestTemplate;",
                "import org.springframework.web.reactive.function.client.WebClient;",
                "import org.springframework.http.client.JdkClientHttpRequestFactory;",
                "import org.springframework.core.io.UrlResource;",
                "import org.apache.commons.net.ftp.FTPClient;",
                "import org.apache.hc.client5.http.classic.HttpClient;",
                "import okhttp3.OkHttpClient;",
                "import org.jsoup.Jsoup;",
                "import org.jsoup.Connection;",
                "import org.jsoup.*;",
                "import static org.jsoup.Jsoup.connect;",
                "import org.jsoup.helper.HttpConnection;",
                "class X { Object o = java.net.http.HttpClient.newHttpClient(); }",
                "class X { void f(java.net.URI u) throws Exception { u.toURL().openStream(); } }",
                "class X { void f(Object u) throws Exception { ((java.net.URL) u).openConnection(); } }",
                "class X { void f(java.nio.file.Path p) throws Exception { p.toUri().toURL(); } }")) {
            // 任意业务模块：不允许
            assertThat(violations("package com.maccms.backend.collect.application;\n" + bad, PAY, RAW)).as(bad).isNotEmpty();
            // 出站客户端所在的 integration.http（及其下级包）：允许
            assertThat(violations("package com.maccms.backend.integration.http;\n" + bad, PAY, RAW)).as(bad).isEmpty();
            assertThat(violations("package com.maccms.backend.integration.http.internal;\n" + bad, PAY, RAW)).as(bad).isEmpty();
            // 已登记的业务子包也不能直接用，必须经出站客户端
            assertThat(violations("package com.maccms.backend.integration.pay;\n" + bad, PAY, RAW)).as(bad).isNotEmpty();
            // 未登记的 integration 子包、integration 根包、名字相近的包同样不允许
            for (String pkg : List.of("integration.sms", "integration", "integration.httpx", "integration.application")) {
                assertThat(violations("package com.maccms.backend." + pkg + ";\n" + bad, PAY, RAW)).as(pkg + " " + bad)
                        .isNotEmpty();
            }
            // 以后若某批把不走 HTTP 的协议子包登记为 RAW_API（如 SMTP），该子包允许
            assertThat(violations("package com.maccms.backend.integration.mail;\n" + bad, PAY,
                    Map.of("integration.http", "B23", "integration.mail", "B25"))).as(bad).isEmpty();
        }

        // 出站客户端只能由已登记子包引用
        for (String use : List.of(
                "import com.maccms.backend.integration.http.OutboundHttpClient;",
                "import com.maccms.backend.integration.http.*;",
                "import static com.maccms.backend.integration.http.OutboundRequest.get;",
                "class X { com.maccms.backend.integration.http.OutboundHttpClient c; }")) {
            assertThat(violations("package com.maccms.backend.integration.pay;\n" + use, PAY, RAW)).as(use).isEmpty();
            assertThat(violations("package com.maccms.backend.integration.pay.alipay;\n" + use, PAY, RAW)).as(use).isEmpty();
            assertThat(violations("package com.maccms.backend.integration.http;\n" + use, PAY, RAW)).as(use).isEmpty();
            for (String pkg : List.of("integration.sms", "integration.payx", "integration", "integration.application",
                    "collect.application", "member.application", "admin.web", "common.web", "site.application")) {
                assertThat(violations("package com.maccms.backend." + pkg + ";\n" + use, PAY, RAW)).as(pkg + " " + use)
                        .isNotEmpty();
            }
            // 登记表为空（B23 交付时）时，除客户端本身外谁都不能用
            assertThat(violations("package com.maccms.backend.integration.pay;\n" + use, Map.of(), RAW)).as(use).isNotEmpty();
        }
        // 注释与字符串里提到客户端不算
        assertThat(violations("package com.maccms.backend.collect.application;\n"
                + "// com.maccms.backend.integration.http.OutboundHttpClient\n"
                + "class X { String s = \"com.maccms.backend.integration.http.OutboundHttpClient\"; }", PAY, RAW)).isEmpty();
        // integration.application 的公开类型（配置、脱敏）谁都可以用
        assertThat(violations("package com.maccms.backend.member.application;\n"
                + "import com.maccms.backend.integration.application.ThirdPartyConfig;\n"
                + "import com.maccms.backend.integration.application.LogRedactor;", PAY, RAW)).isEmpty();
    }

    private static final Map<String, String> PAY = Map.of("integration.pay", "B24");
    private static final Map<String, String> RAW = Map.of("integration.http", "B23");

    /**
     * 返回一个源文件的违规：所在包不在 {@code raw} 中却用了出站 API；所在包既不是客户端包、也不在 {@code registered} 中，
     * 却引用了出站客户端。
     */
    static List<String> violations(String source, Map<String, String> registered, Map<String, String> raw) {
        String pkg = JavaSources.modulePackage(source);
        String code = JavaSources.stripCommentsAndStrings(source);
        List<String> out = new ArrayList<>();
        if (!IntegrationPackages.isRegistered(pkg, raw.keySet())) {
            Matcher t = OUTBOUND_TYPES.matcher(code);
            while (t.find()) {
                out.add("出站网络类型 " + t.group(1));
            }
            Matcher c = OUTBOUND_CALLS.matcher(code);
            while (c.find()) {
                out.add("出站调用 ." + c.group(1) + "(");
            }
        }
        if (!IntegrationPackages.isRegistered(pkg, List.of(IntegrationPackages.CLIENT))
                && !IntegrationPackages.isRegistered(pkg, registered.keySet())) {
            Matcher r = CLIENT_REF.matcher(code);
            while (r.find()) {
                out.add("未登记的包引用出站客户端 " + r.group());
            }
        }
        return out;
    }
}
