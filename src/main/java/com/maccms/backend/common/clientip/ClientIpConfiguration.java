package com.maccms.backend.common.clientip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link ClientIpResolver}：始终注册（Provide 白名单首先使用，B08/B09/B11 复用），参数不合法时启动失败。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientIpProperties.class)
public class ClientIpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ClientIpConfiguration.class);

    @Bean
    ClientIpResolver clientIpResolver(ClientIpProperties properties) {
        ClientIpResolver resolver = ClientIpResolver.from(properties);
        // 只记录项数与头名，不记录地址
        log.info("client ip: {} trusted proxy entries, forwarded header {}", resolver.trustedProxyCount(),
                resolver.headerName());
        return resolver;
    }
}
