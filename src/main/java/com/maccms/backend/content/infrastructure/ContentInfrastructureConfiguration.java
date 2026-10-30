package com.maccms.backend.content.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * content 模块的写 Mapper（B11）：可写数据源打开时（{@link ConditionalOnWriteEnabled}）注册内容计数与搜索缓存的写 Mapper；关闭时没有这些
 * Bean，计数接口只能只读（写入的请求按"功能暂未开放"处理，20 文档第 3 节）。
 */
@Configuration(proxyBeanMethods = false)
public class ContentInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    ContentCounterWriteMapper contentCounterWriteMapper(WriteDatabase db) {
        return db.mapper(ContentCounterWriteMapper.class);
    }

    @Bean
    @ConditionalOnWriteEnabled
    VodSearchWriteMapper vodSearchWriteMapper(WriteDatabase db) {
        return db.mapper(VodSearchWriteMapper.class);
    }

    /** B13 后台内容管理的写入（22 文档第 6 节）。 */
    @Bean
    @ConditionalOnWriteEnabled
    ContentAdminWrites contentAdminWrites(WriteDatabase db) {
        return new ContentAdminWrites(db.mapper(ContentAdminWriteMapper.class));
    }
}
