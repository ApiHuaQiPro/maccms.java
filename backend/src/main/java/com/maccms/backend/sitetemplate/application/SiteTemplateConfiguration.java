package com.maccms.backend.sitetemplate.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.site.application.SiteTemplateSource;
import com.maccms.backend.sitetemplate.infrastructure.BuiltinTemplateSources;
import com.maccms.backend.sitetemplate.infrastructure.JteClassPath;
import com.maccms.backend.sitetemplate.infrastructure.JteOnlineCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 注册 {@link SiteTemplateService}（前台用它的 {@link SiteTemplateSource}，后台模板管理用它保存与编译），供 site 与 admin 的装配类引入。
 * 没有开启在线编辑时不建工作目录、不编译，前台一直用内置模板。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SiteTemplateProperties.class)
@Import(LegacyConfigConfiguration.class)
public class SiteTemplateConfiguration {

    @Bean
    SiteTemplateService siteTemplateService(SiteTemplateProperties properties, LegacySiteFiles files, LegacyConfigService config) {
        ClassLoader loader = SiteTemplateSource.class.getClassLoader();
        JteOnlineCompiler compiler = null;
        if (properties.enabled() && JteOnlineCompiler.available()) {
            Path work = workDir(properties.workDir());
            compiler = new JteOnlineCompiler(work, JteClassPath.resolve(work, SiteTemplateSource.class, gg.jte.Content.class,
                    gg.jte.html.HtmlTemplateOutput.class), loader);
        }
        SiteTemplateService service = new SiteTemplateService(properties.enabled(), files, config, compiler,
                BuiltinTemplateSources.load(loader));
        service.start();
        return service;
    }

    private static Path workDir(String configured) {
        try {
            if (configured == null || configured.isBlank()) {
                return Files.createTempDirectory("maccms-site-template-");
            }
            Path p = Path.of(configured.strip()).toAbsolutePath().normalize();
            Files.createDirectories(p);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("maccms.site-template.work-dir", e);
        }
    }
}
