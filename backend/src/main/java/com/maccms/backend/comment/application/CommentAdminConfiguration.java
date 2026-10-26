package com.maccms.backend.comment.application;

import com.maccms.backend.comment.infrastructure.CommentAdminMapper;
import com.maccms.backend.comment.infrastructure.CommentAdminWrites;
import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B13 后台评论与留言管理的服务（22 文档第 2 节）：只供 admin 模块调用。后台要求可写数据源打开，所以只在可写数据源打开时注册。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWriteEnabled
public class CommentAdminConfiguration {

    @Bean
    CommentAdminService commentAdminService(CommentAdminMapper reader, CommentAdminWrites writer, ObjectProvider<Clock> clock) {
        return new CommentAdminService(reader, writer, clock.getIfAvailable(Clock::systemUTC));
    }
}
