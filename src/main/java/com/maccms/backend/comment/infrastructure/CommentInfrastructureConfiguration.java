package com.maccms.backend.comment.infrastructure;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.common.write.WriteDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * comment 模块的写 Mapper（B11）：可写数据源打开时注册；关闭时没有这个 Bean，评论与留言只能浏览，发表、举报、顶踩按"功能暂未开放"处理。
 */
@Configuration(proxyBeanMethods = false)
public class CommentInfrastructureConfiguration {

    @Bean
    @ConditionalOnWriteEnabled
    CommentWriteMapper commentWriteMapper(WriteDatabase db) {
        return db.mapper(CommentWriteMapper.class);
    }

    /** B13 后台评论与留言管理的写入。 */
    @Bean
    @ConditionalOnWriteEnabled
    CommentAdminWrites commentAdminWrites(WriteDatabase db) {
        return new CommentAdminWrites(db.mapper(CommentAdminWriteMapper.class));
    }
}
