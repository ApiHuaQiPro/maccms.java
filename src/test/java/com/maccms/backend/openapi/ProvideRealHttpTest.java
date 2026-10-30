package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Provide 白名单的真实 HTTP 用例（B06，D2 ④，16 文档第 2、3.2 节；验收第 3 条）：完整应用 + 真实 Tomcat + 原始套接字，伪造的头原样发送。
 * 应用只信任可信代理 127.0.0.1：本机连接代表"经过可信代理"，X-Forwarded-For 是代理给出的转发链。白名单为 198.51.100.7 与
 * 2001:db8::7（内置 127.0.0.1）。数据读取是替身（拒绝路径在查库之前），数据源指向不可达的合成地址。
 */
@SpringBootTest(classes = {MaccmsBackendApplication.class, ProvideRealHttpTest.Settings.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
        "spring.datasource.username=synthetic_ro",
        "spring.datasource.password=synthetic",
        "server.address=127.0.0.1",
        "maccms.openapi.enabled=true",
        "maccms.client-ip.trusted-proxies=127.0.0.1"})
class ProvideRealHttpTest {

    private static final String LIST = "/api.php/provide/vod/?ac=list";

    @TestConfiguration
    static class Settings {
        @Bean
        ProvideSettingsSource goldenSettings() {
            return () -> ProvideHttp.settings("198.51.100.7#2001:DB8::7#whitelisted.example.test#198.51.100.8:80");
        }
    }

    @LocalServerPort
    int port;
    @MockitoBean
    LegacyRowReadService rows;

    @BeforeEach
    void setUp() {
        when(rows.read(any())).thenReturn(ProvideHttp.empty());
        when(rows.types()).thenReturn(List.of());
    }

    private ProvideHttp.Response get(String path, String... headers) throws Exception {
        return ProvideHttp.get("127.0.0.1", port, path, headers);
    }

    private static void assertAllowed(ProvideHttp.Response r) {
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("application/json;charset=utf-8");
        assertThat(r.headers().get("cache-control")).isEqualTo("private, max-age=60");
        assertThat(r.body()).startsWith("{\"code\":1,\"msg\":\"数据列表\"");
    }

    private static void assertDenied(ProvideHttp.Response r) {
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("text/plain;charset=utf-8");
        assertThat(r.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(r.headers().get("x-content-type-options")).isEqualTo("nosniff");
        assertThat(r.body()).isEqualTo("域名未授权");
    }

    @Test
    void trustedProxyForwardingAWhitelistedClientIsAllowed() throws Exception {
        assertAllowed(get(LIST, "X-Forwarded-For: 198.51.100.7"));
        assertAllowed(get(LIST, "X-Forwarded-For: 2001:db8:0:0:0:0:0:7"));
        // 多跳：代理在右侧追加真实客户端；最左项是客户端自己写的，不被采用
        assertAllowed(get(LIST, "X-Forwarded-For: 203.0.113.9, 198.51.100.7"));
        // 同名头出现多次按顺序连接
        assertAllowed(get(LIST, "X-Forwarded-For: 203.0.113.9", "X-Forwarded-For: 198.51.100.7"));
        // 没有转发头：对端 127.0.0.1（旧版内置白名单）
        assertAllowed(get(LIST));
    }

    @Test
    void forgedHeadersCannotImpersonateAWhitelistedClient() throws Exception {
        String real = "X-Forwarded-For: 203.0.113.9";
        for (String forged : List.of("CF-Connecting-IP", "Ali-CDN-Real-IP", "Client-IP", "Proxy-User", "X-Real-IP",
                "True-Client-IP")) {
            assertDenied(get(LIST, real, forged + ": 198.51.100.7"));
            assertDenied(get(LIST, real, forged + ": 127.0.0.1"));
        }
        assertDenied(get(LIST, real, "Forwarded: for=198.51.100.7"));
        // 客户端自己写在最左边的白名单地址不被采用
        assertDenied(get(LIST, "X-Forwarded-For: 198.51.100.7, 203.0.113.9"));
    }

    @Test
    void domainAndPortEntriesNeverMatchAndIpv6IsNormalized() throws Exception {
        // 域名项不解析（服务端不发 DNS 查询），带端口的项永不命中
        assertDenied(get(LIST, "X-Forwarded-For: 198.51.100.8"));
        assertDenied(get(LIST, "X-Forwarded-For: 2001:db8::8"));
        // 不在白名单中的 IPv6
        assertDenied(get(LIST, "X-Forwarded-For: ::ffff:203.0.113.9"));
    }

    @Test
    void forwardedValuesThatNameNoClientAreDeniedInsteadOfFallingBackToTheProxy() throws Exception {
        // 对端 127.0.0.1 是可信代理，也在旧版内置白名单里：头给不出客户端时结果是 0.0.0.0，不退回 127.0.0.1
        for (String bad : List.of("unknown", "198.51.100.7:443", "[2001:db8::7]", "whitelisted.example.test", "198.51.100.7,",
                ", ", "0.0.0.0", "::")) {
            assertDenied(get(LIST, "X-Forwarded-For: " + bad));
        }
        // 非法项右边是不在白名单中的客户端：用那个客户端
        assertDenied(get(LIST, "X-Forwarded-For: unknown, 203.0.113.9"));
        // 非法项左边的白名单地址不被采用
        assertDenied(get(LIST, "X-Forwarded-For: 198.51.100.7, unknown"));
    }

    @Test
    void oversizedForwardedChainsCannotBypassTheWhitelist() throws Exception {
        // 独立验收复现：超过 32 跳或 2048 字节曾让整个头作废并退回对端 127.0.0.1（命中内置白名单）
        String hops33 = String.join(", ", java.util.Collections.nCopies(33, "203.0.113.9"));
        assertDenied(get(LIST, "X-Forwarded-For: " + hops33));
        String trusted33 = String.join(", ", java.util.Collections.nCopies(33, "127.0.0.1"));
        assertDenied(get(LIST, "X-Forwarded-For: " + trusted33));
        assertDenied(get(LIST, "X-Forwarded-For: " + "x".repeat(3000)));
        assertDenied(get(LIST, "X-Forwarded-For: " + "x".repeat(3000) + ", 203.0.113.9"));
        // 左边填满白名单地址也不行：只看最右边，代理追加的真实客户端不在白名单
        assertDenied(get(LIST, "X-Forwarded-For: " + String.join(",", java.util.Collections.nCopies(200, "198.51.100.7"))
                + ", 203.0.113.9"));
        // 反过来，白名单客户端在左边填垃圾仍被识别
        assertAllowed(get(LIST, "X-Forwarded-For: " + "x".repeat(3000) + ", 198.51.100.7"));
    }

    @Test
    void headRequestHasNoBody() throws Exception {
        ProvideHttp.Response r = ProvideHttp.request("HEAD", "127.0.0.1", port, "/api.php/provide/art/?ac=list");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEmpty();
        assertThat(r.headers().get("content-length")).isNotNull();
        assertThat(r.headers().get("x-content-type-options")).isEqualTo("nosniff");
    }

    @Test
    void freeModulesIgnoreTheWhitelistAndClosedModulesSayClosed() throws Exception {
        ProvideHttp.Response art = get("/api.php/provide/art/?ac=list", "X-Forwarded-For: 203.0.113.9");
        assertThat(art.status()).isEqualTo(200);
        assertThat(art.headers().get("cache-control")).isEqualTo("no-store");
        ProvideHttp.Response actor = get("/api.php/provide/actor/?ac=list", "X-Forwarded-For: 203.0.113.9");
        assertThat(actor.status()).isEqualTo(200);
        assertThat(actor.body()).isEqualTo("closed");
        assertThat(actor.headers().get("content-type")).isEqualToIgnoringCase("text/plain;charset=utf-8");
    }
}
