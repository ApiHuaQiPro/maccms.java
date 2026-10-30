package com.maccms.backend.legacyconfig.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 旧配置只读层的部署参数 {@code maccms.legacy-config.*}，只来自本应用的环境变量（application.yml）。
 * 按原始字符串绑定，校验在 {@link LegacyConfigService} 中进行，错误只报配置项名称、不回显取值。
 *
 * @param dir 旧站 {@code application/} 目录（其中有 {@code extra/*.php} 与 {@code route.php}），可以在仓库之外；
 *            留空表示未配置，读取时抛 {@link LegacyConfigException.Reason#NOT_CONFIGURED}
 */
@ConfigurationProperties(prefix = "maccms.legacy-config")
public record LegacyConfigProperties(String dir) {
}
