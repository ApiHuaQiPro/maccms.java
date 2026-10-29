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

    private final OutboundPolicy open = new OutboundPolicy(OutboundPolicy.Mode.OPEN);
    private final OutboundPolicy loopback = new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY);

    @Test
    void openModeChecksFormatOnly() {
        assertThat(open.check(URI.create("https://api.provider.test/v1/x?sign=abc"))).isEqualTo("api.provider.test");
        assertThat(open.check(URI.create("http://203.0.113.1:8080/"))).isEqualTo("203.0.113.1:8080");
        assertThat(open.check(URI.create("HTTPS://Api.Provider.Test/"))).isEqualTo("Api.Provider.Test");
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

    @Test
    void loopbackOnlyModeAllowsOnlyLoopbackLiterals() {
        for (String ok : List.of("http://127.0.0.1:1/", "http://127.255.0.9/", "http://localhost:8/", "http://[::1]:9/",
                "https://LOCALHOST/")) {
            assertThat(loopback.check(URI.create(ok))).as(ok).isNotEmpty();
        }
        for (String bad : List.of("https://api.provider.test/", "http://10.0.0.1/", "http://128.0.0.1/", "http://127.0.0.256/",
                "http://localhost.provider.test/", "http://[::2]/", "http://0.0.0.0/")) {
            assertThatThrownBy(() -> loopback.check(URI.create(bad))).as(bad).isInstanceOf(OutboundException.class);
        }
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
