package com.maccms.backend.integration.testing;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * 测试期出站护栏（B23 ⑥，路线图第 4.3 节 ②；26 文档第 7 节）：{@code ./mvnw verify} 中任何对非回环地址的连接都让测试失败。
 *
 * <p>两道拦截，都只在测试 JVM 里生效：
 * <ul>
 * <li>默认 {@link ProxySelector} 换成本类：JDK 的 {@code HttpClient}、{@code HttpURLConnection}、{@code Socket}（含 JDBC 驱动）
 * 建立连接前都会询问它，目标不是回环地址时登记违规并让连接失败；</li>
 * <li>名称解析（{@link LoopbackOnlyResolverProvider}，经 {@code META-INF/services} 装入）：除 {@code localhost} 与本机名外的
 * 主机名一律解析失败并登记违规，所以按主机名连接的任何客户端（含 NIO）都连不出去，也不会发出 DNS 查询。</li>
 * </ul>
 * 违规由 {@link OutboundGuardExtension}（自动注册的 JUnit 扩展）在每个测试之后检查，有违规即判该测试失败。
 * 已知缺口：NIO {@code SocketChannel} 直接连 IP 字面地址不经过上面两道（不问代理也不解析名称）；这类 API 在 {@code src/main}
 * 中只能出现在 {@code integration.http}，由 {@code OutboundNetworkGuardTest} 静态拦截。
 */
public final class TestOutboundGuard extends ProxySelector {

    private static final TestOutboundGuard INSTANCE = new TestOutboundGuard();
    private static final ConcurrentLinkedQueue<String> VIOLATIONS = new ConcurrentLinkedQueue<>();
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(\\.\\d{1,3}){3}");

    private TestOutboundGuard() {
    }

    /** 安装为默认代理选择器（幂等）。名称解析的一道由 JDK 在第一次解析时经 ServiceLoader 装入。 */
    public static synchronized void install() {
        if (ProxySelector.getDefault() != INSTANCE) {
            ProxySelector.setDefault(INSTANCE);
        }
    }

    /** 当前默认代理选择器是否是本护栏。 */
    public static boolean isInstalled() {
        return ProxySelector.getDefault() == INSTANCE;
    }

    /** 取出并清空已登记的违规。 */
    public static List<String> drainViolations() {
        List<String> out = new ArrayList<>();
        for (String v = VIOLATIONS.poll(); v != null; v = VIOLATIONS.poll()) {
            out.add(v);
        }
        return out;
    }

    static void record(String what) {
        VIOLATIONS.add(what);
    }

    /** 回环主机：{@code localhost}、{@code *.localhost}、127.0.0.0/8、{@code ::1}（含方括号与全写形式）。只看字面，不解析。 */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.equals("localhost") || h.endsWith(".localhost") || IPV4_LOOPBACK.matcher(h).matches()
                || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    @Override
    public List<Proxy> select(URI uri) {
        String host = hostOf(uri);
        if (isLoopbackHost(host)) {
            return List.of(Proxy.NO_PROXY);
        }
        String target = uri == null ? "null"
                : uri.getScheme() + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        record("连接非回环地址 " + target);
        throw new IllegalArgumentException("测试期出站护栏：不允许连接非回环地址 " + target);
    }

    /**
     * 目标主机。{@code Socket} 连 IPv6 地址时 JDK 给出的是 {@code socket://%5b0:0:0:0:0:0:0:1%5d:8080}（方括号被编码，按 URI 规则
     * 解析不出主机），这时取解码后的 authority 去掉最后的 {@code :端口}。
     */
    static String hostOf(URI uri) {
        if (uri == null) {
            return null;
        }
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        // 解码后的 authority：JDK 构造这个 URI 时把方括号编码成 %5b/%5d
        String auth = uri.getAuthority();
        if (auth == null) {
            return null;
        }
        int colon = auth.lastIndexOf(':');
        return colon > 0 ? auth.substring(0, colon) : auth;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
    }
}
