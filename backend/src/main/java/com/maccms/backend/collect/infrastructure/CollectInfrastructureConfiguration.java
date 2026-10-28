package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * collect 模块的写入（B17、B19 入库）、本地文件源（B18）与入库断点（B19 增量 3）：可写数据源打开时注册；关闭时没有这些 Bean（后台本身要求可写数据源打开）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectLocalProperties.class)
public class CollectInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    CollectAdminWrites collectAdminWrites(WriteDatabase db) {
        return new CollectAdminWrites(db.mapper(CollectAdminWriteMapper.class));
    }

    @Bean
    @ConditionalOnWriteEnabled
    CollectIngestWrites collectIngestWrites(WriteDatabase db) {
        return new CollectIngestWrites(db.mapper(CollectIngestWriteMapper.class));
    }

    @Bean
    @ConditionalOnWriteEnabled
    FileProvidePreviewStore fileProvidePreviewStore(CollectLocalProperties p) {
        return new FileProvidePreviewStore(p.cachePath());
    }

    @Bean
    @ConditionalOnWriteEnabled
    FileCollectBreakpointStore fileCollectBreakpointStore(CollectLocalProperties p) {
        return new FileCollectBreakpointStore(p.cachePath());
    }

    @Bean
    @ConditionalOnWriteEnabled
    DirectoryProvideSourceFiles directoryProvideSourceFiles(CollectLocalProperties p) {
        return new DirectoryProvideSourceFiles(p.sourcePath());
    }
}
