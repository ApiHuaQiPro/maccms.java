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
 * 未配置可信代理（默认）时的真实 HTTP 用例（B06，16 文档第 2.2 节第 2 条；验收第 3 条）：应用只监听 IPv6 回环 {@code ::1}，连接对端
 * 就是 {@code ::1}，不在白名单内（旧版内置的只有 127.0.0.1）。任何转发头（包括 X-Forwarded-For）都不能把客户端 IP 改成白名单地址。
 */
@SpringBootTest(classes = {MaccmsBackendApplication.class, ProvideRealHttpNoProxyTest.Settings.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
        "spring.datasource.username=synthetic_ro",
        "spring.datasource.password=synthetic",
        "server.address=::1",
        "maccms.openapi.enabled=true"})
class ProvideRealHttpNoProxyTest {

    private static final String LIST = "/api.php/provide/vod/?ac=list";

    @TestConfiguration
    static class Settings {
        @Bean
        ProvideSettingsSource goldenSettings() {
            return () -> ProvideHttp.settings("198.51.100.7");
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

    @Test
    void withoutTrustedProxiesNoHeaderCanChangeTheClientAddress() throws Exception {
        for (String h : List.of("X-Forwarded-For: 198.51.100.7", "X-Forwarded-For: 127.0.0.1", "CF-Connecting-IP: 127.0.0.1",
                "Ali-CDN-Real-IP: 198.51.100.7", "Client-IP: 127.0.0.1", "Proxy-User: 198.51.100.7",
                "X-Real-IP: 127.0.0.1", "True-Client-IP: 198.51.100.7")) {
            ProvideHttp.Response r = ProvideHttp.get("::1", port, LIST, h);
            assertThat(r.status()).as(h).isEqualTo(403);
            assertThat(r.body()).as(h).isEqualTo("域名未授权");
        }
        assertThat(ProvideHttp.get("::1", port, LIST).status()).isEqualTo(403);
        // 免费模块不看白名单
        assertThat(ProvideHttp.get("::1", port, "/api.php/provide/art/?ac=list").status()).isEqualTo(200);
    }
}
