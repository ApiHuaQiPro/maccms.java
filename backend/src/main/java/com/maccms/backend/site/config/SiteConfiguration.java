package com.maccms.backend.site.config;

import com.maccms.backend.common.browser.BrowserExternalAccess;
import com.maccms.backend.common.browser.BrowserExternalConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SiteImageProperties;
import com.maccms.backend.site.application.SiteLegacyConfig;
import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.application.SiteUrlProperties;
import com.maccms.backend.site.web.SiteHtmlRenderer;
import com.maccms.backend.site.web.SitePages;
import com.maccms.backend.site.web.SiteStatusInterceptor;
import com.maccms.backend.site.web.jte.StaticTemplates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties({SiteProperties.class, SiteUrlProperties.class, SiteImageProperties.class})
@Import({BrowserExternalConfiguration.class, LegacyConfigConfiguration.class})
public class SiteConfiguration {

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
    SitePages sitePages(SiteSettings settings, LegacyUrlBuilder urls) {
        return new SitePages(settings, urls);
    }

    @Bean
    SiteHtmlRenderer siteHtmlRenderer() {
        return new SiteHtmlRenderer(new StaticTemplates());
    }

    @Bean
    WebMvcConfigurer siteStatusWebMvcConfigurer(SiteSettings settings) {
        SiteStatusInterceptor interceptor = new SiteStatusInterceptor(settings);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
