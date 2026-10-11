package com.maccms.backend.site.config;

import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
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
 * site 模块装配，仅在 {@code maccms.site.enabled=true} 时生效。站点配置在这里校验，
 * 缺失或不合法时启动失败（{@link SiteSettings#from}）。
 *
 * <p>模板使用构建期预编译的类（{@link StaticTemplates}），运行时不编译模板、不读取模板文件，
 * 不引入 jte 的 Spring Boot starter 与视图解析器（页面不走边渲染边输出的视图路径）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSiteEnabled
@EnableConfigurationProperties(SiteProperties.class)
public class SiteConfiguration {

    @Bean
    SiteSettings siteSettings(SiteProperties properties) {
        return SiteSettings.from(properties);
    }

    @Bean
    SitePages sitePages(SiteSettings settings) {
        return new SitePages(settings);
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
