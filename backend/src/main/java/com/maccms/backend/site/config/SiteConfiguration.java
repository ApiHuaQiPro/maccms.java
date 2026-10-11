package com.maccms.backend.site.config;

import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SiteImageProperties;
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
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * site 模块装配，仅在 {@code maccms.site.enabled=true} 时生效。站点配置（{@code maccms.site.*}，含 URL 形态
 * {@code maccms.site.url.*} 与图片地址 {@code maccms.site.image.*}）在这里校验，缺失或不合法时启动失败，
 * 错误信息只列配置项名称（{@link SiteSettings#from}、{@link SiteUrlProperties#toStyle}、{@link SiteImageProperties#toNormalizer}）。
 *
 * <p>模板使用构建期预编译的类（{@link StaticTemplates}），运行时不编译模板、不读取模板文件，
 * 不引入 jte 的 Spring Boot starter 与视图解析器（页面不走边渲染边输出的视图路径）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSiteEnabled
@EnableConfigurationProperties({SiteProperties.class, SiteUrlProperties.class, SiteImageProperties.class})
public class SiteConfiguration {

    @Bean
    SiteSettings siteSettings(SiteProperties properties) {
        return SiteSettings.from(properties);
    }

    @Bean
    LegacyUrlBuilder siteLegacyUrlBuilder(SiteUrlProperties url) {
        return new LegacyUrlBuilder(url.toStyle());
    }

    @Bean
    LegacyUrlParser siteLegacyUrlParser(LegacyUrlBuilder builder) {
        return new LegacyUrlParser(builder);
    }

    @Bean
    ImageUrlNormalizer siteImageUrlNormalizer(SiteImageProperties image, SiteSettings settings) {
        return image.toNormalizer(settings);
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
