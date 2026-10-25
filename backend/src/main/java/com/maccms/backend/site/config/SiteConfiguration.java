package com.maccms.backend.site.config;

import com.maccms.backend.common.browser.BrowserExternalAccess;
import com.maccms.backend.common.browser.BrowserExternalConfiguration;
import com.maccms.backend.common.captcha.CaptchaSettings;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.site.application.BrowserAddress;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.PasswordAttempts;
import com.maccms.backend.site.application.PasswordTickets;
import com.maccms.backend.site.application.PlayerSettings;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SearchTimespan;
import com.maccms.backend.site.application.SiteFeatures;
import com.maccms.backend.site.application.SiteMiscSettings;
import com.maccms.backend.site.application.SiteImageProperties;
import com.maccms.backend.site.application.SiteLegacyConfig;
import com.maccms.backend.site.application.SitePasswordProperties;
import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.application.SiteUrlProperties;
import com.maccms.backend.site.web.SearchThrottle;
import com.maccms.backend.site.web.SiteHtmlRenderer;
import com.maccms.backend.site.web.SitePages;
import com.maccms.backend.site.web.SiteSessions;
import com.maccms.backend.site.web.SiteMemberCacheFilter;
import com.maccms.backend.site.web.SiteMemberInterceptor;
import com.maccms.backend.site.web.SiteStatusInterceptor;
import com.maccms.backend.site.web.jte.StaticTemplates;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * site 模块装配，仅在 {@code maccms.site.enabled=true} 时生效。站点配置（{@code maccms.site.*}，含 URL 形态
 * {@code maccms.site.url.*} 与图片地址 {@code maccms.site.image.*}）在这里校验，缺失或不合法时启动失败，
 * 错误信息只列配置项名称（{@link SiteSettings#from}、{@link SiteUrlProperties#toStyle}、{@link SiteImageProperties#toNormalizer}）。
 * 配置了旧配置目录时，各项先按字段并入旧配置 {@code extra/maccms.php} 的值（{@link SiteLegacyConfig}，B02），再走同样的校验。
 *
 * <p>页面用的图片规范化器按部署参数 {@code maccms.browser-external.w2}（{@link BrowserExternalAccess}，默认打开）决定是否输出
 * 外部主机的图片地址；关闭时外部图片按空值处理（显示占位），正文里的外部图片整张删除（B02，11 文档第 5 节）。
 *
 * <p>模板使用构建期预编译的类（{@link StaticTemplates}），运行时不编译模板、不读取模板文件，
 * 不引入 jte 的 Spring Boot starter 与视图解析器（页面不走边渲染边输出的视图路径）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSiteEnabled
@EnableConfigurationProperties({SiteProperties.class, SiteUrlProperties.class, SiteImageProperties.class,
        SitePasswordProperties.class, SiteFeatures.Properties.class})
@Import({BrowserExternalConfiguration.class, LegacyConfigConfiguration.class})
public class SiteConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SiteConfiguration.class);

    /** 中文码表缺字形告警最多列出的码位数。 */
    static final int MISSING_GLYPHS_LOGGED = 16;

    /**
     * 站点设置的来源：设置了 {@code maccms.legacy-config.dir} 时，启动时读一次旧配置 {@code extra/maccms.php}，
     * 按字段并入 {@code maccms.site.*}（环境变量优先，{@link SiteLegacyConfig}）；读取或解析失败时启动失败。
     * 运行中不热加载，改了旧配置要重启（site 设置的热加载在 B15）。未设置目录时只用环境变量，与 B02 之前相同。
     */
    @Bean
    SiteLegacyConfig.Merged siteConfigSources(SiteProperties site, SiteUrlProperties url, SiteImageProperties image,
                                              LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return SiteLegacyConfig.none(site, url, image);
        }
        PhpArray maccms;
        try {
            maccms = legacy.read(LegacyConfigFile.MACCMS);
        } catch (LegacyConfigException e) {
            // 信息只含原因与行列，不含文件内容
            throw new IllegalStateException("site 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
        return SiteLegacyConfig.merge(site, url, image, maccms);
    }

    @Bean
    SiteSettings siteSettings(SiteLegacyConfig.Merged sources) {
        return sources.validate(() -> SiteSettings.from(sources.site()));
    }

    @Bean
    LegacyUrlBuilder siteLegacyUrlBuilder(SiteLegacyConfig.Merged sources) {
        return new LegacyUrlBuilder(sources.validate(() -> sources.url().toStyle()));
    }

    @Bean
    LegacyUrlParser siteLegacyUrlParser(LegacyUrlBuilder builder) {
        return new LegacyUrlParser(builder);
    }

    @Bean
    ImageUrlNormalizer siteImageUrlNormalizer(SiteLegacyConfig.Merged sources, SiteSettings settings,
                                              BrowserExternalAccess external) {
        return sources.validate(() -> sources.image().toNormalizer(settings)).withExternalImages(external.w2());
    }

    @Bean
    RichTextCache siteRichTextCache(ImageUrlNormalizer images) {
        return new RichTextCache(RichTextSanitizer.create(images), RichTextCache.DEFAULT_MAX_ENTRIES,
                RichTextCache.DEFAULT_MAX_CHARS);
    }

    @Bean
    SitePages sitePages(SiteSettings settings, LegacyUrlBuilder urls, BrowserExternalAccess external) {
        return new SitePages(settings, urls, external.w2());
    }

    /**
     * 旧播放器配置（B05）：配置了旧配置目录时启动时读一次 {@code extra/maccms.php}、{@code vodplayer.php}、{@code voddowner.php}、
     * {@code vodserver.php}（读取或解析失败、取值不受支持时启动失败，信息不含文件内容）；没有配置时播放/下载页不可用。
     */
    @Bean
    PlayerSettings sitePlayerSettings(LegacyConfigService legacy, SiteSettings settings, BrowserExternalAccess external) {
        if (!legacy.isConfigured()) {
            return PlayerSettings.unavailable();
        }
        PhpArray maccms;
        PhpArray players;
        PhpArray downers;
        PhpArray servers;
        try {
            maccms = legacy.read(LegacyConfigFile.MACCMS);
            players = legacy.read(LegacyConfigFile.VODPLAYER);
            downers = legacy.read(LegacyConfigFile.VODDOWNER);
            servers = legacy.read(LegacyConfigFile.VODSERVER);
        } catch (LegacyConfigException e) {
            if (e.reason() == LegacyConfigException.Reason.NOT_FOUND) {
                // 旧配置目录里没有播放器配置文件：播放/下载页不可用（404），其余页面照常；格式错误仍启动失败
                log.warn("legacy player config not found; play and download pages are unavailable");
                return PlayerSettings.unavailable();
            }
            throw new IllegalStateException("site 已启用但旧播放器配置读取失败：" + e.getMessage(), null);
        }
        return PlayerSettings.from(maccms, players, downers, servers, external.w1(),
                BrowserAddress.hostOf(settings.baseUrl()));
    }

    /** 旧 app.ajax_page、app.show 两个页面开关（B05）：环境变量优先，其次旧配置，都没有时关闭。 */
    @Bean
    SiteFeatures siteFeatures(SiteFeatures.Properties properties, LegacyConfigService legacy) {
        PhpArray maccms = null;
        if (legacy.isConfigured()) {
            try {
                maccms = legacy.read(LegacyConfigFile.MACCMS);
            } catch (LegacyConfigException e) {
                throw new IllegalStateException("site 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
            }
        }
        return SiteFeatures.from(properties, maccms);
    }

    /** B06 杂项页用到的旧配置（版权提示文案、联想词开关）：启动时读一次，没有旧配置时按仓库值。 */
    @Bean
    SiteMiscSettings siteMiscSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return SiteMiscSettings.from(null);
        }
        try {
            return SiteMiscSettings.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            throw new IllegalStateException("site 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
    }

    /** 访问密码签名票据（B05，D-B05-3）：部署密钥只来自环境变量，留空时不提供在线验证。 */
    @Bean
    PasswordTickets sitePasswordTickets(SitePasswordProperties properties) {
        return PasswordTickets.from(properties.secret(), Clock.systemUTC());
    }

    @Bean
    PasswordAttempts sitePasswordAttempts() {
        return new PasswordAttempts(Clock.systemUTC());
    }

    /** 进程内会话（B08）：验证码与搜索计时。重启即清空。 */
    @Bean
    SessionStore siteSessionStore() {
        return SessionStore.withDefaults(Clock.systemUTC());
    }

    @Bean
    SiteSessions siteSessions(SessionStore store, SiteSettings settings) {
        return new SiteSessions(store, settings.homePath().href(), settings.baseUrl().href().startsWith("https:"));
    }

    /**
     * 验证码图片参数（B08）：配置了旧配置目录时启动时读一次 {@code extra/captcha.php}（文件不存在时用 think-captcha 默认值；格式错误
     * 或取值不受支持时启动失败，信息不含文件内容）；没有配置时按仓库值。
     */
    @Bean
    CaptchaSettings siteCaptchaSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 4L, "fontSize", 16L,
                    "useNoise", false, "useCurve", false));
        }
        PhpArray captcha;
        try {
            captcha = legacy.read(LegacyConfigFile.CAPTCHA);
        } catch (LegacyConfigException e) {
            if (e.reason() == LegacyConfigException.Reason.NOT_FOUND) {
                log.warn("legacy captcha config not found; using think-captcha defaults");
                return CaptchaSettings.THINK_DEFAULTS;
            }
            throw new IllegalStateException("site 已启用但旧验证码配置 extra/captcha.php 读取失败：" + e.getMessage(), null);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        captcha.entries().forEach((k, v) -> values.put(String.valueOf(k), scalar(v)));
        CaptchaSettings settings = CaptchaSettings.fromLegacy(values);
        List<Integer> missing = settings.missingZhGlyphs();
        if (!missing.isEmpty()) {
            // 只在装配时记一次，最多列 MISSING_GLYPHS_LOGGED 个码位；这些字照常抽取与校验，图上画缺字框
            log.warn("legacy captcha zhSet has {} characters without built-in glyphs (drawn as missing-glyph boxes): {}",
                    missing.size(), missing.stream().limit(MISSING_GLYPHS_LOGGED)
                            .map(cp -> String.format("U+%04X", cp)).collect(Collectors.joining(" "))
                            + (missing.size() > MISSING_GLYPHS_LOGGED ? " ..." : ""));
        }
        return settings;
    }

    /** 验证码的随机源（B08）：字符与版式都用不可预测的随机数（旧版用 {@code mt_rand}）。 */
    @Bean
    RandomGenerator siteCaptchaRandom() {
        return new SecureRandom();
    }

    /** 旧 {@code app.search_timespan}（B08）：启动时读一次，没有旧配置时不限制。 */
    @Bean
    SearchTimespan siteSearchTimespan(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return SearchTimespan.OFF;
        }
        try {
            return SearchTimespan.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            throw new IllegalStateException("site 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
    }

    @Bean
    SearchThrottle siteSearchThrottle(SiteSessions sessions, SearchTimespan timespan, SiteHtmlRenderer renderer,
                                      SitePages pages) {
        return new SearchThrottle(sessions, timespan, renderer, pages);
    }

    private static Object scalar(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpString s -> s.value();
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> f.value();
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpNull n -> null;
            case PhpArray a -> null;
        };
    }

    @Bean
    SiteHtmlRenderer siteHtmlRenderer() {
        return new SiteHtmlRenderer(new StaticTemplates());
    }

    /**
     * 站点状态（维护页）在前、会员 {@code label_user} 接线在后（B09，旧版先 check_site_status 再 label_user，Base.php:11-18）。
     * 会员服务在只装配部分 Bean 的测试里可能没有，此时一律按游客。
     */
    @Bean
    WebMvcConfigurer siteStatusWebMvcConfigurer(SiteSettings settings, ObjectProvider<MemberSessionService> members,
                                                ObjectProvider<MemberRecordService> records) {
        SiteStatusInterceptor interceptor = new SiteStatusInterceptor(settings);
        SiteMemberInterceptor member = new SiteMemberInterceptor(members, records);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
                registry.addInterceptor(member);
            }
        };
    }

    /** 会员页面的缓存头替换（B09，{@link SiteMemberCacheFilter}）。 */
    @Bean
    FilterRegistrationBean<SiteMemberCacheFilter> siteMemberCacheFilter() {
        FilterRegistrationBean<SiteMemberCacheFilter> bean = new FilterRegistrationBean<>(new SiteMemberCacheFilter());
        bean.addUrlPatterns("/*");
        return bean;
    }
}
