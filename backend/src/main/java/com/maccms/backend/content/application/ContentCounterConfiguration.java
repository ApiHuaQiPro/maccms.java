package com.maccms.backend.content.application;

import com.maccms.backend.content.infrastructure.ContentCounterMapper;
import com.maccms.backend.content.infrastructure.ContentCounterWriteMapper;
import com.maccms.backend.content.infrastructure.VodSearchMapper;
import com.maccms.backend.content.infrastructure.VodSearchWriteMapper;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B11 的内容计数与视频搜索缓存服务（20 文档第 5、6 节）。可写数据源关闭时写 Mapper 不存在，服务只能只读。时钟取容器里的
 * {@link Clock}（没有时为系统时钟；集成测试用它把"现在"固定到黄金样本的时刻）。
 */
@Configuration(proxyBeanMethods = false)
public class ContentCounterConfiguration {

    @Bean
    ContentCounterService contentCounterService(ContentCounterMapper reader,
                                                ObjectProvider<ContentCounterWriteMapper> writer,
                                                ObjectProvider<Clock> clock) {
        return new ContentCounterService(reader, writer.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    VodSearchCache vodSearchCache(VodSearchMapper reader, ObjectProvider<VodSearchWriteMapper> writer,
                                  ObjectProvider<Clock> clock) {
        return new VodSearchCache(reader, writer.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    VodSearchOptimise vodSearchOptimise(ObjectProvider<LegacyConfigService> legacy) {
        return new VodSearchOptimise(legacy.getIfAvailable());
    }

    /** 后台"清理缓存"用到的内容侧清理（B12）。 */
    @Bean
    ContentCacheMaintenance contentCacheMaintenance(VodSearchCache cache, VodSearchOptimise optimise) {
        return new ContentCacheMaintenance(cache, optimise);
    }
}
