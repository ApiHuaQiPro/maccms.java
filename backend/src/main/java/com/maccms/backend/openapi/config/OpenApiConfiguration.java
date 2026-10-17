package com.maccms.backend.openapi.config;

import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.openapi.application.ProvideModule;
import com.maccms.backend.openapi.application.ProvideService;
import com.maccms.backend.openapi.application.ProvideSettings;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provide（B06，docs/architecture/16-b06-misc-pages-provide.md 第 3 节）的装配：设置了 {@code maccms.legacy-config.dir} 时启动时读一次
 * 旧配置 {@code extra/maccms.php} 的 {@code api.*}（改了要重启）；读取、解析失败或取值不受支持时启动失败。未设置目录时全部模块关闭
 * （输出 {@code closed}）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnOpenApiEnabled
public class OpenApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfiguration.class);

    @Bean
    ProvideSettings provideSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            log.warn("openapi enabled without legacy config; every provide module is closed");
            return ProvideSettings.closed();
        }
        ProvideSettings settings;
        try {
            settings = ProvideSettings.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            // 信息只含原因与行列，不含文件内容
            throw new IllegalStateException("openapi 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
        for (ProvideModule m : ProvideModule.values()) {
            ProvideSettings.ModuleSettings ms = settings.module(m);
            if (ms.ignoredAuth() > 0) {
                // 只记录项数：旧版对域名项做 DNS 解析（Provide.php:793-795），新版服务端不出站
                log.warn("provide {}: {} auth entries are not IP literals and never match", m.key(), ms.ignoredAuth());
            }
            if (ms.open() && ms.detailIncHits()) {
                log.warn("provide {}: detail_inc_hits is on but hit counting is not implemented in this batch (B11)",
                        m.key());
            }
        }
        return settings;
    }

    /**
     * 时钟可替换（测试用固定时钟重放 PHP 黄金样本的 h 参数）；旧配置来源可替换（容器中另有 {@link ProvideSettingsSource} 时用它，
     * 测试用来重放多组旧配置，生产没有这个 Bean）。
     */
    @Bean
    ProvideService provideService(ProvideSettings settings, LegacyRowReadService rows, ObjectProvider<Clock> clock,
                                  ObjectProvider<ProvideSettingsSource> source) {
        ProvideSettingsSource s = source.getIfAvailable(() -> () -> settings);
        return new ProvideService(s, rows, clock.getIfAvailable(Clock::systemUTC));
    }
}
