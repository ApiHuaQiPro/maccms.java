package com.maccms.backend.common.write;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link WriteDatabase}：只有 {@code maccms.write.enabled=true} 时创建（默认不创建，应用保持只读）。
 * 使用正式登记表 {@link WriteRegistry#registered()}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WriteDataSourceProperties.class)
public class WriteDataSourceConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "maccms.write", name = "enabled", havingValue = "true")
    WriteDatabase writeDatabase(WriteDataSourceProperties properties,
                                @Value("${spring.datasource.url:}") String readUrl) {
        return WriteDatabase.open(WriteDataSourceSettings.from(properties, readUrl), WriteRegistry.registered());
    }
}
