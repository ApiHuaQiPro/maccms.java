package com.maccms.backend.integration.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第三方接口配置的装配（B23）：启动时经 B02 的 {@link LegacyConfigService} 读一次 {@code extra/maccms.php}，
 * 后台修改后重启生效（D-B15-18，与 site、会员等设置同一口径）。未配置旧配置目录时得到全空的配置（与旧版字段为空时相同）。
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationConfiguration {

    @Bean
    ThirdPartyConfig thirdPartyConfig(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return ThirdPartyConfig.from(null);
        }
        try {
            return ThirdPartyConfig.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            // 信息只含原因与行列，不含文件内容
            throw new IllegalStateException("第三方接口配置读取旧配置 extra/maccms.php 失败：" + e.getMessage(), null);
        }
    }
}
