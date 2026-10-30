package com.maccms.backend.staticgen.config;

import com.maccms.backend.admin.application.PlayerAdminService;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.collect.application.CjColAll;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.collect.application.CollectAdminServices;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.UrlsendSourceService;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.StaticPageSource;
import com.maccms.backend.staticgen.application.MakeService;
import com.maccms.backend.staticgen.application.StaticFiles;
import com.maccms.backend.staticgen.application.TimmingService;
import com.maccms.backend.staticgen.application.TimmingTasks;
import com.maccms.backend.staticgen.application.UrlsendService;
import com.maccms.backend.staticgen.web.TimmingAccess;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 静态生成与定时任务（B16，25 文档第 2 节）的装配。
 *
 * <ul>
 * <li>{@link MakeService} 总是注册：数据来自 content（{@link StaticSourceService}，需要只读数据源），页面来自前台
 * （{@link StaticPageSource}，前台未启用时没有，生成时逐个报告失败），写文件经 {@link StaticFiles}。后台 {@code make/*} 用它。</li>
 * <li>定时任务入口 {@code TimmingController} 只在 {@code maccms.timming.enabled=true} 时注册；口令配置不合法时启动失败（不回显取值）。</li>
 * <li>语言包按旧配置 {@code app.lang}（启动时读一次，同后台）；时区为旧站 {@code application/config.php} 的 {@code PRC}。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimmingProperties.class)
public class StaticgenConfiguration {

    private static final ZoneId LEGACY_ZONE = MakeService.LEGACY_ZONE;

    @Bean
    StaticFiles staticgenFiles(LegacySiteFiles files) {
        return new StaticFiles(files);
    }

    /** 旧配置 {@code app.lang} 选出的语言包（启动时读一次）。 */
    static LegacyLang lang(LegacyConfigService legacy) {
        String code = "";
        if (legacy.isConfigured()) {
            LegacyConfigService.Snapshot s = legacy.snapshotOrNull(LegacyConfigFile.MACCMS);
            code = s == null ? "" : PhpValues.text(s.value().path("app", "lang"));
        }
        return LegacyLang.select(code).pack();
    }

    @Bean
    MakeService staticgenMakeService(LegacyConfigService legacy, ObjectProvider<StaticSourceService> sources,
                                     ObjectProvider<StaticPageSource> pages, StaticFiles files) {
        return new MakeService(legacy, sources::getIfAvailable, pages::getIfAvailable, files, lang(legacy), Clock.systemUTC(),
                LEGACY_ZONE);
    }

    /**
     * URL 推送（B28）：后台 {@code urlsend/push}、{@code urlsend/data} 与定时任务 {@code urlsend} 共用。数据来自 content（只读数据源），推送经
     * {@code integration.urlsend}；都在执行时取，缺哪个就按"没有获取到数据"/"参数错误"报告。
     */
    @Bean
    UrlsendService staticgenUrlsendService(LegacyConfigService legacy, ObjectProvider<UrlsendSourceService> source,
                                           ObjectProvider<StaticSourceService> types, ObjectProvider<UrlPush> push) {
        return new UrlsendService(legacy, source::getIfAvailable, types::getIfAvailable, push::getIfAvailable, lang(legacy),
                Clock.systemUTC(), LEGACY_ZONE);
    }

    @Bean
    TimmingService staticgenTimmingService(LegacyConfigService legacy) {
        return new TimmingService(legacy, lang(legacy), Clock.systemUTC(), LEGACY_ZONE);
    }

    /**
     * 定时任务里的 {@code cache}：同后台"清理缓存"（重写 playerconfig.js 的缓存段、清理过期的视频搜索缓存，有哪个做哪个）；{@code cj}：B20 规则采集的一轮
     * （{@link CjColAll#timming}，执行时取采集服务，采集模块未启用时没有）；{@code urlsend}：URL 推送（B28）。
     */
    @Bean
    TimmingTasks staticgenTimmingTasks(MakeService make, ObjectProvider<PlayerAdminService> players,
                                       ObjectProvider<ContentCacheMaintenance> contentCache,
                                       ObjectProvider<CollectAdminServices> collect, UrlsendService urlsend,
                                       LegacyConfigService legacy) {
        LegacyLang lang = lang(legacy);
        return new TimmingTasks(make, () -> {
            PlayerAdminService p = players.getIfAvailable();
            if (p != null) {
                p.clearCache();
            }
            ContentCacheMaintenance c = contentCache.getIfAvailable();
            if (c != null) {
                c.clearVodSearchCache();
            }
        }, param -> {
            CollectAdminServices c = collect.getIfAvailable();
            CjRunService cj = c == null ? null : c.cj();
            return cj == null ? null : CjColAll.timming(cj, param, lang::get);
        }, urlsend, lang);
    }

    @Bean
    TimmingAccess staticgenTimmingAccess(ClientIpResolver ips, TimmingProperties properties) {
        return new TimmingAccess(ips, properties.token());
    }
}
