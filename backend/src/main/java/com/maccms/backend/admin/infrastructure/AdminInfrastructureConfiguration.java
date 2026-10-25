package com.maccms.backend.admin.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 后台模块的基础设施装配（B12）。写 Mapper 只在可写数据源打开时注册（与 B07 {@link WriteDatabase} 同条件）；后台启用而可写数据源
 * 关闭时，后台装配在启动时失败（AdminConfiguration）。
 */
@Configuration(proxyBeanMethods = false)
public class AdminInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    AdminWriteMapper adminWriteMapper(WriteDatabase db) {
        return db.mapper(AdminWriteMapper.class);
    }
}
