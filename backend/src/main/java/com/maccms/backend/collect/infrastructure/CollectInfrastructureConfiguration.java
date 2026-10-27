package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** collect 模块的写入（B17）：可写数据源打开时注册；关闭时没有这个 Bean（后台本身要求可写数据源打开）。 */
@Configuration(proxyBeanMethods = false)
public class CollectInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    CollectAdminWrites collectAdminWrites(WriteDatabase db) {
        return new CollectAdminWrites(db.mapper(CollectAdminWriteMapper.class));
    }
}
