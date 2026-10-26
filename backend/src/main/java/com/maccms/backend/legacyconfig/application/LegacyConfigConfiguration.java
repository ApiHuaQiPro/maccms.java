package com.maccms.backend.legacyconfig.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 注册 {@link LegacyConfigService}、{@link LegacySiteFiles}（B15）与 {@link LegacyConfigProperties}，供其他模块的装配类引入（如 site 在切片测试中也能拿到）。
 * 与组件扫描同时存在时 Spring 只保留一份。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LegacyConfigProperties.class)
@Import({LegacyConfigService.class, LegacySiteFiles.class})
public class LegacyConfigConfiguration {
}
