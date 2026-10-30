package com.maccms.backend.member.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会员模块的基础设施装配（B09）。
 *
 * <p>写 Mapper：可写数据源打开时（{@link ConditionalOnWriteEnabled}，与 B07 {@link WriteDatabase} 同条件创建）注册会员写 Mapper；关闭时没有这个
 * Bean，会员账号入口按 503 处理（18 文档第 2 节）。按属性而不是 {@code @ConditionalOnBean} 判定，避免依赖配置类的处理顺序。
 */
@Configuration(proxyBeanMethods = false)
public class MemberInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    MemberWriteMapper memberWriteMapper(WriteDatabase db) {
        return db.mapper(MemberWriteMapper.class);
    }

    /** 会员权益与财务的写 Mapper（B10，19 文档第 6 节），条件同上。 */
    @Bean
    @ConditionalOnWriteEnabled
    MemberFinanceWriteMapper memberFinanceWriteMapper(WriteDatabase db) {
        return db.mapper(MemberFinanceWriteMapper.class);
    }

    /** 后台会员与财务管理的写入（B14，23 文档第 6 节），条件同上。 */
    @Bean
    @ConditionalOnWriteEnabled
    MemberAdminWrites memberAdminWrites(WriteDatabase db) {
        return new MemberAdminWrites(db.mapper(MemberAdminWriteMapper.class), db.mapper(MemberFinanceWriteMapper.class));
    }

    /** 头像目录（部署参数 {@code maccms.member.upload-dir}，默认空 = 头像上传不可用）。 */
    @Bean
    PortraitStore portraitStore(@Value("${maccms.member.upload-dir:}") String uploadDir) {
        return new PortraitStore(uploadDir);
    }
}
