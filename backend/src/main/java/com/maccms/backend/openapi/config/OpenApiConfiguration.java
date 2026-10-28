package com.maccms.backend.openapi.config;

import com.maccms.backend.content.application.ContentCounterService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.openapi.application.ProvideModule;
import com.maccms.backend.openapi.application.ProvideService;
import com.maccms.backend.openapi.application.ProvideSettings;
import com.maccms.backend.openapi.application.ProvideSettingsSource;
import com.maccms.backend.openapi.application.WechatService;
import com.maccms.backend.openapi.application.WechatSettings;
import com.maccms.backend.openapi.application.WechatSettingsSource;
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
 *
 * <p>B27 起同时装配微信公众号接口（docs/architecture/29-b27-wechat.md 第 3 节）：同样启动时读一次 {@code weixin}、{@code rewrite}、
 * {@code upload} 等配置，未设置目录时接口关闭。
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
        }
        return settings;
    }

    /**
     * 时钟可替换（测试用固定时钟重放 PHP 黄金样本的 h 参数）；旧配置来源可替换（容器中另有 {@link ProvideSettingsSource} 时用它，
     * 测试用来重放多组旧配置，生产没有这个 Bean）。
     */
    @Bean
    ProvideService provideService(ProvideSettings settings, LegacyRowReadService rows, ObjectProvider<Clock> clock,
                                  ObjectProvider<ProvideSettingsSource> source,
                                  ObjectProvider<ContentCounterService> counters) {
        ProvideSettingsSource s = source.getIfAvailable(() -> () -> settings);
        ContentCounterService c = counters.getIfAvailable();
        boolean incHits = false;
        for (ProvideModule m : ProvideModule.values()) {
            incHits |= settings.module(m).open() && settings.module(m).detailIncHits();
        }
        if (incHits && (c == null || !c.writable())) {
            // B11 起 detail_inc_hits 写 vod_hits；可写数据源关闭时不计数
            log.warn("provide vod: detail_inc_hits is on but the write datasource is disabled; hits are not counted");
        }
        return new ProvideService(s, rows, clock.getIfAvailable(Clock::systemUTC), c);
    }

    @Bean
    WechatSettings wechatSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            log.warn("openapi enabled without legacy config; wechat is closed");
            return WechatSettings.closed();
        }
        WechatSettings settings;
        try {
            settings = WechatSettings.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            throw new IllegalStateException("openapi 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
        if (!settings.urlStyleSupported()) {
            log.warn("legacy rewrite settings not supported for wechat links; repository defaults used");
        }
        return settings;
    }

    /** 旧配置来源可替换（同 Provide：测试用来重放多组旧配置，生产没有这个 Bean）；时钟可替换。 */
    @Bean
    WechatService wechatService(WechatSettings settings, ObjectProvider<WechatSettingsSource> source,
                                ContentReadService content, LegacyRowReadService rows, ObjectProvider<Clock> clock) {
        return new WechatService(source.getIfAvailable(() -> () -> settings), content, rows,
                clock.getIfAvailable(Clock::systemUTC));
    }
}
