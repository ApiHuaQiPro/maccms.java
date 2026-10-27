package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.CollectAdminMapper;
import com.maccms.backend.collect.infrastructure.CollectAdminWrites;
import com.maccms.backend.collect.infrastructure.CollectLocalProperties;
import com.maccms.backend.collect.infrastructure.DirectoryProvideSourceFiles;
import com.maccms.backend.collect.infrastructure.FileProvidePreviewStore;
import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.content.application.ContentAdminServices;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B17 后台采集配置管理的服务（25 文档第 2 节）与 B18 本地文件源的解析与预览（26 文档第 2 节，预览缓存与本地目录见
 * {@link CollectLocalProperties}）。后台要求可写数据源打开（21 文档第 7 节），所以只在可写数据源打开时注册。分类绑定读写旧配置
 * {@code extra/bind.php}（经 {@link LegacyConfigService}），取本站分类名用 content 模块的分类表（旧 {@code getCache('type_list')}）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWriteEnabled
public class CollectAdminConfiguration {

    @Bean
    CollectAdminServices collectAdminServices(CollectAdminMapper reader, CollectAdminWrites writer,
                                              ObjectProvider<LegacyConfigService> legacy,
                                              ObjectProvider<ContentAdminServices> content, ObjectProvider<Clock> clock,
                                              FileProvidePreviewStore previewStore, DirectoryProvideSourceFiles sourceFiles,
                                              CollectLocalProperties local) {
        TypeNames types = id -> {
            ContentAdminServices c = content.getIfAvailable();
            if (c == null) {
                return Optional.empty();
            }
            AdminRow r = c.types().cacheList().get(id);
            return r == null ? Optional.empty() : Optional.of(r.s("type_name"));
        };
        CollectBindService binds = new CollectBindService(legacy.getIfAvailable(), types);
        Clock c = clock.getIfAvailable(Clock::systemUTC);
        CollectPreviewService preview = new CollectPreviewService(previewStore, sourceFiles, binds, types, legacy.getIfAvailable(), c,
                local.cacheTtl(), local.maxFileBytes(), local.maxEntries());
        return new CollectAdminServices(new CollectSourceAdminService(reader, writer), binds, new CjNodeAdminService(reader, writer, c),
                preview);
    }
}
