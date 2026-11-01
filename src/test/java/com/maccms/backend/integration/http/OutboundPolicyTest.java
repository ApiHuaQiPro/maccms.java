package com.maccms.backend.integration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationConfiguration;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 出站目标检查与部署参数（B23 ②）。 */
class OutboundPolicyTest {

    /** 公网地址（文档外的普通单播）；测试不做真实 DNS 查询。 */
    private static final String PUBLIC_IP = "93.184.216.34";
    private final java.util.Map<String, String[]> dns = new java.util.HashMap<>(java.util.Map.of(
            "api.provider.test", new String[] {PUBLIC_IP}));
    private final OutboundPolicy open = new OutboundPolicy(OutboundPolicy.Mode.OPEN, this::resolve);

    private java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
        String[] ips = dns.get(host);
        if (ips == null) {
            // 字面地址：与 InetAddress.getAllByName 相同，不查 DNS
            if (host.matches("[0-9.]+") || host.contains(":")) {
                return java.net.InetAddress.getAllByName(host);
            }
            throw new java.net.UnknownHostException("test");
        }
        java.net.InetAddress[] out = new java.net.InetAddress[ips.length];
        for (int i = 0; i < ips.length; i++) {
            out[i] = java.net.InetAddress.getByName(ips[i]);
        }
        return out;
    }
    private final OutboundPolicy loopback = new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY);

    @Test
    void openModeChecksFormatOnly() {
        assertThat(open.check(URI.create("https://api.provider.test/v1/x?sign=abc")).logHost()).isEqualTo("api.provider.test");
        assertThat(open.check(URI.create("http://93.184.216.34:8080/")).logHost()).isEqualTo("93.184.216.34:8080");
        assertThat(open.check(URI.create("HTTPS://Api.Provider.Test/")).logHost()).isEqualTo("Api.Provider.Test");
        for (String bad : List.of("ftp://api.provider.test/", "file:///etc/passwd", "mailto:a@b.test", "/relative",
                "http:///nohost", "jar:http://h.test/x.jar!/a")) {
            assertThatThrownBy(() -> open.check(URI.create(bad))).as(bad).isInstanceOfSatisfying(OutboundException.class,
                    e -> assertThat(e.reason()).isEqualTo(OutboundException.Reason.TARGET_REJECTED));
        }
        // 凭证不进 URL：带用户名密码一律拒绝，信息里只有主机
        assertThatThrownBy(() -> open.check(URI.create("https://u:test-only-pw@api.provider.test/")))
                .isInstanceOfSatisfying(OutboundException.class, e -> {
                    assertThat(e.reason()).isEqualTo(OutboundException.Reason.TARGET_REJECTED);
                    assertThat(e.getMessage()).isEqualTo("TARGET_REJECTED host=api.provider.test").doesNotContain("test-only");
                });
        assertThatThrownBy(() -> open.check(null)).isInstanceOf(OutboundException.class);
    }

    /** APPK-2 OUT-1-1～OUT-1-4（SSRF）：片段、内部端口、禁止主机名、解析后地址类别、非常规 IPv4 写法、解析失败。 */
    @Test
    void openModeRejectsInternalTargetsAfterResolution() {
        dns.put("rebind.provider.test", new String[] {PUBLIC_IP, "10.1.2.3"});
        dns.put("meta.provider.test", new String[] {"169.254.169.254"});
        dns.put("v6.provider.test", new String[] {"2606:2800:220:1:248:1893:25c8:1946"});
        dns.put("v6private.provider.test", new String[] {"fd00::1"});
        assertThat(open.check(URI.create("https://v6.provider.test/x")).logHost()).isEqualTo("v6.provider.test");
        assertThat(open.check(URI.create("http://[2606:2800:220:1:248:1893:25c8:1946]:8080/")).logHost())
                .isEqualTo("[2606:2800:220:1:248:1893:25c8:1946]:8080");
        assertThat(open.check(URI.create("https://api.provider.test:443/")).logHost()).isEqualTo("api.provider.test:443");
        for (String bad : List.of(
                // OUT-1-1：片段
                "https://api.provider.test/a#frag",
                // OUT-1-2：内部服务端口
                "http://api.provider.test:22/", "http://api.provider.test:3306/", "http://api.provider.test:6379/",
                "http://api.provider.test:0/", "http://api.provider.test:11211/", "http://api.provider.test:27017/",
                // OUT-1-4：禁止主机名
                "http://localhost/", "http://LOCALHOST./", "http://a.localhost/", "http://metadata/", "http://metadata.google.internal/",
                // OUT-1-4：IPv4 禁止段（字面）
                "http://127.0.0.1/", "http://127.9.9.9/", "http://0.0.0.0/", "http://10.0.0.1/", "http://172.16.0.1/",
                "http://172.31.255.255/", "http://192.168.1.1/", "http://100.64.0.1/", "http://169.254.169.254/latest/meta-data/",
                "http://192.0.0.1/", "http://192.0.2.1/", "http://198.18.0.1/", "http://198.19.255.1/", "http://198.51.100.1/",
                "http://203.0.113.1/", "http://224.0.0.1/", "http://239.1.1.1/", "http://240.0.0.1/", "http://255.255.255.255/",
                // OUT-1-4：IPv6 禁止段与内嵌 IPv4
                "http://[::1]/", "http://[::]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.1]/", "http://[::127.0.0.1]/",
                "http://[64:ff9b::a00:1]/", "http://[fc00::1]/", "http://[fd12:3456::1]/", "http://[fe80::1]/", "http://[ff02::1]/",
                // OUT-1-3：非常规 IPv4 写法
                "http://2130706433/", "http://0x7f000001/", "http://0x7f.0.0.1/", "http://0177.0.0.1/", "http://127.1/",
                "http://010.0.0.1/", "http://1.2.3/",
                // OUT-1-3：解析结果中任一地址被禁止即整体拒绝；解析到云元数据；解析失败
                "http://rebind.provider.test/", "http://meta.provider.test/", "http://v6private.provider.test/",
                "http://unknown.provider.test/")) {
            java.util.List<String> accepted = new java.util.ArrayList<>();
            try {
                open.check(URI.create(bad));
                accepted.add(bad);
            } catch (OutboundException e) {
                assertThat(e.reason()).as(bad).isEqualTo(OutboundException.Reason.TARGET_REJECTED);
            }
            assertThat(accepted).isEmpty();
        }
        // 公网 IPv4 映射 IPv6、NAT64 内嵌公网地址：按内嵌地址判断
        assertThat(open.check(URI.create("http://[::ffff:8.8.8.8]/")).logHost()).isEqualTo("[::ffff:8.8.8.8]");
        assertThat(OutboundPolicy.forbidden(address("::1"))).isTrue();
        assertThat(OutboundPolicy.forbidden(address("::"))).isTrue();
        assertThat(OutboundPolicy.forbidden(address("64:ff9b::808:808"))).isFalse();
        assertThat(OutboundPolicy.forbidden(address("64:ff9b::7f00:1"))).isTrue();
        assertThat(OutboundPolicy.forbidden(address("8.8.8.8"))).isFalse();
        assertThat(OutboundPolicy.forbidden(address("172.32.0.1"))).isFalse();
        assertThat(OutboundPolicy.forbidden(address("100.128.0.1"))).isFalse();
        // OUT-1-9：信息只含 host[:port]，不含路径与查询串
        assertThatThrownBy(() -> open.check(URI.create("http://10.0.0.1:8080/x?sign=abc"))).hasMessage("TARGET_REJECTED host=10.0.0.1:8080");
    }

    private static java.net.InetAddress address(String literal) {
        try {
            return java.net.InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void loopbackOnlyModeAllowsOnlyLoopbackLiterals() {
        for (String ok : List.of("http://127.0.0.1:1/", "http://127.255.0.9/", "http://localhost:8/", "http://[::1]:9/",
                "https://LOCALHOST/")) {
            assertThat(loopback.check(URI.create(ok)).logHost()).as(ok).isNotEmpty();
        }
        for (String bad : List.of("https://api.provider.test/", "http://10.0.0.1/", "http://128.0.0.1/", "http://127.0.0.256/",
                "http://localhost.provider.test/", "http://[::2]/", "http://0.0.0.0/", "http://127.0.0.1/#x")) {
            assertThatThrownBy(() -> loopback.check(URI.create(bad))).as(bad).isInstanceOf(OutboundException.class);
        }
    }

    /** APPK-2 OUT-1-5（NET-7 ①）：检查结果带着规范化主机与已检查的地址，建连只用这组地址。 */
    @Test
    void targetCarriesNormalizedHostAndCheckedAddresses() {
        dns.put("multi.provider.test", new String[] {PUBLIC_IP, "2606:2800:220:1:248:1893:25c8:1946"});
        OutboundPolicy.Target t = open.check(URI.create("https://Multi.Provider.Test./x"));
        assertThat(t.logHost()).isEqualTo("Multi.Provider.Test.");
        assertThat(t.host()).isEqualTo("multi.provider.test");
        assertThat(t.addresses()).extracting(java.net.InetAddress::getHostAddress)
                .containsExactly(PUBLIC_IP, "2606:2800:220:1:248:1893:25c8:1946");
        assertThat(open.check(URI.create("http://[2606:2800:220:1:248:1893:25c8:1946]:8080/")).host())
                .isEqualTo("2606:2800:220:1:248:1893:25c8:1946");

        assertThat(loopback.check(URI.create("http://127.255.0.9/")).addresses())
                .extracting(java.net.InetAddress::getHostAddress).containsExactly("127.255.0.9");
        assertThat(loopback.check(URI.create("http://[::1]:9/")).addresses())
                .extracting(java.net.InetAddress::getHostAddress).containsExactly("0:0:0:0:0:0:0:1");
        OutboundPolicy.Target local = loopback.check(URI.create("https://LOCALHOST/"));
        assertThat(local.host()).isEqualTo("localhost");
        assertThat(local.addresses()).extracting(java.net.InetAddress::getHostAddress)
                .containsExactly("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    @Test
    void modeParsing() {
        assertThat(OutboundPolicy.Mode.parse(null)).isEqualTo(OutboundPolicy.Mode.OPEN);
        assertThat(OutboundPolicy.Mode.parse(" ")).isEqualTo(OutboundPolicy.Mode.OPEN);
        assertThat(OutboundPolicy.Mode.parse("open")).isEqualTo(OutboundPolicy.Mode.OPEN);
        assertThat(OutboundPolicy.Mode.parse(" Loopback-Only ")).isEqualTo(OutboundPolicy.Mode.LOOPBACK_ONLY);
        assertThatThrownBy(() -> OutboundPolicy.Mode.parse("off")).isInstanceOf(IllegalStateException.class)
                .hasMessage("maccms.integration.outbound.mode 只能是 open 或 loopback-only");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LegacyConfigConfiguration.class, IntegrationConfiguration.class,
                    OutboundClientConfiguration.class);

    @Test
    void wiringDefaultsAndValidation() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OutboundHttpClient.class).mode()).isEqualTo(OutboundPolicy.Mode.OPEN);
        });
        runner.withPropertyValues("maccms.integration.outbound.mode=loopback-only",
                "maccms.integration.outbound.connect-timeout=1s", "maccms.integration.outbound.read-timeout=2s",
                "maccms.integration.outbound.max-response-bytes=1024")
                .run(ctx -> assertThat(ctx.getBean(OutboundHttpClient.class).mode())
                        .isEqualTo(OutboundPolicy.Mode.LOOPBACK_ONLY));
        for (String bad : List.of("maccms.integration.outbound.mode=none", "maccms.integration.outbound.read-timeout=0s",
                "maccms.integration.outbound.connect-timeout=-1s", "maccms.integration.outbound.max-response-bytes=0")) {
            runner.withPropertyValues(bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }

    /** 测试配置（classpath:/config/application.properties，叠加在 application.yml 之上）把出站模式设为只允许回环地址。 */
    @Test
    void testConfigurationUsesLoopbackOnly() throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            assertThat(in).isNotNull();
            p.load(in);
        }
        assertThat(p.getProperty("maccms.integration.outbound.mode")).isEqualTo("loopback-only");
    }

    /**
     * B23 交付时没有任何业务子包登记，B28 登记 urlsend 与 backlink，B25 登记 sms 与 mail，B24 登记 pay，B26 登记 oauth，B29 登记 addonmarket；
     * 可以直接用出站 API 的只有客户端本身与 SMTP 发信的 mail（32 文档第 4.1 节）。
     */
    @Test
    void registryAtB29() {
        assertThat(IntegrationPackages.REGISTERED).containsOnly(java.util.Map.entry("integration.urlsend", "B28"),
                java.util.Map.entry("integration.backlink", "B28"), java.util.Map.entry("integration.sms", "B25"),
                java.util.Map.entry("integration.mail", "B25"), java.util.Map.entry("integration.pay", "B24"),
                java.util.Map.entry("integration.oauth", "B26"), java.util.Map.entry("integration.addonmarket", "B29"));
        assertThat(IntegrationPackages.RAW_API).containsOnly(java.util.Map.entry("integration.http", "B23"),
                java.util.Map.entry("integration.mail", "B25"));
        assertThat(IntegrationPackages.CLIENT).isEqualTo(
                IntegrationPackages.modulePackage(OutboundHttpClient.class.getPackageName()));
    }
}
