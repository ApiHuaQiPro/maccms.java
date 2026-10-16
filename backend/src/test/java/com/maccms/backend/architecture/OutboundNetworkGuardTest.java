package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 出站护栏（B02，路线图第 1.3、4.3 节；docs/architecture/11-guards-and-legacy-config.md 第 2 节）：
 * 扫描 src/main/java 中的出站网络 API，它们只允许出现在 {@link #REGISTERED} 登记的 {@code integration} 子包里。
 *
 * <p>B02 交付时登记表为空。B23 起每个外部调用批次只登记本批的子包（{@code integration.pay}、{@code integration.sms} 等），
 * 登记改动必须出现在该批的合并提交中。E01/E02/E03/E08 的本地版本、采集链、上传与图片处理永远不登记。
 * E05 支付回调、E12 公众号是入站接口，不需要登记。
 *
 * <p>扫描的是去掉注释与字符串后的代码，覆盖导入、全限定名与通配导入；另外按方法名拦截不需要导入就能调用的
 * {@code .openConnection(}、{@code .openStream(}、{@code .toURL(}。同时检查 pom.xml 不引入出站客户端依赖。
 * 规则由 {@link #rulesOnSyntheticSources()} 自检。
 */
class OutboundNetworkGuardTest {

    /** 允许使用出站 API 的子包（相对 com.maccms.backend）→ 登记批次。只能是 integration 的子包。 */
    static final Map<String, String> REGISTERED = Map.of();

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
        assertThat(REGISTERED.keySet()).allSatisfy(p -> assertThat(p).matches("integration\\.[a-z][a-z0-9]*"));
    }

    @Test
    void mainSourcesUseOutboundApisOnlyInRegisteredPackages() throws IOException {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : JavaSources.mainJava().entrySet()) {
            for (String v : violations(e.getValue(), REGISTERED)) {
                all.add(e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("出站护栏：出站网络 API 只能出现在已登记的 integration 子包").isEmpty();
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
        assertThat(violations(ok, Map.of())).isEmpty();

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
            String src = "package com.maccms.backend.collect.application;\n" + bad;
            assertThat(violations(src, Map.of())).as(bad).isNotEmpty();
            // 同一段代码放进已登记的 integration 子包则允许
            String registered = "package com.maccms.backend.integration.pay;\n" + bad;
            assertThat(violations(registered, Map.of("integration.pay", "B24"))).as(bad).isEmpty();
            // 未登记的 integration 子包、或登记子包之外的 integration 包仍不允许
            assertThat(violations("package com.maccms.backend.integration.sms;\n" + bad,
                    Map.of("integration.pay", "B24"))).as(bad).isNotEmpty();
            assertThat(violations("package com.maccms.backend.integration;\n" + bad,
                    Map.of("integration.pay", "B24"))).as(bad).isNotEmpty();
        }
        // 已登记子包的下级包同样允许（如 integration.pay.alipay）
        assertThat(violations("package com.maccms.backend.integration.pay.alipay;\nimport java.net.http.HttpClient;",
                Map.of("integration.pay", "B24"))).isEmpty();
        // 名字相似的包不算登记（integration.payx）
        assertThat(violations("package com.maccms.backend.integration.payx;\nimport java.net.http.HttpClient;",
                Map.of("integration.pay", "B24"))).isNotEmpty();
    }

    /** 返回一个源文件使用出站 API 的位置；所在包已登记时返回空。 */
    static List<String> violations(String source, Map<String, String> registered) {
        String pkg = JavaSources.modulePackage(source);
        if (pkg != null) {
            for (String allowed : registered.keySet()) {
                if (pkg.equals(allowed) || pkg.startsWith(allowed + ".")) {
                    return List.of();
                }
            }
        }
        String code = JavaSources.stripCommentsAndStrings(source);
        List<String> out = new ArrayList<>();
        Matcher t = OUTBOUND_TYPES.matcher(code);
        while (t.find()) {
            out.add("出站网络类型 " + t.group(1));
        }
        Matcher c = OUTBOUND_CALLS.matcher(code);
        while (c.find()) {
            out.add("出站调用 ." + c.group(1) + "(");
        }
        return out;
    }
}
