package com.maccms.backend.common.browser;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link BrowserExternalAccess}：始终注册（不依赖 site 是否启用），参数不合法时启动失败。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrowserExternalProperties.class)
public class BrowserExternalConfiguration {

    @Bean
    BrowserExternalAccess browserExternalAccess(BrowserExternalProperties properties) {
        return BrowserExternalAccess.from(properties);
    }
}
