package com.maccms.backend.integration.http;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 出站客户端的装配（B23）。参数不合法时启动失败，信息只含配置项名称；第三方配置中的密钥值交给客户端，写日志前按原文遮盖。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundClientConfiguration {

    @Bean
    OutboundHttpClient outboundHttpClient(OutboundProperties p, ThirdPartyConfig config) {
        OutboundPolicy policy = new OutboundPolicy(OutboundPolicy.Mode.parse(p.mode()));
        Duration connect = positive(p.connectTimeout(), OutboundProperties.DEFAULT_CONNECT_TIMEOUT,
                "maccms.integration.outbound.connect-timeout");
        Duration read = positive(p.readTimeout(), OutboundProperties.DEFAULT_READ_TIMEOUT,
                "maccms.integration.outbound.read-timeout");
        int max = p.maxResponseBytes() == null ? OutboundProperties.DEFAULT_MAX_RESPONSE_BYTES : p.maxResponseBytes();
        if (max <= 0) {
            throw new IllegalStateException("maccms.integration.outbound.max-response-bytes 必须大于 0");
        }
        return new OutboundHttpClient(policy, IntegrationPackages.REGISTERED.keySet(), connect, read, max,
                config.secretValues());
    }

    private static Duration positive(Duration d, Duration fallback, String name) {
        if (d == null) {
            return fallback;
        }
        if (d.isNegative() || d.isZero()) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
        return d;
    }
}
