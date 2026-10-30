package com.maccms.backend.comment.application;

import com.maccms.backend.comment.infrastructure.CommentMapper;
import com.maccms.backend.comment.infrastructure.CommentWriteMapper;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评论与留言服务的装配（B11）：与会员服务相同，只在站点页面启用时创建。可写数据源关闭时写 Mapper 不存在，{@code writable()} 为 false。
 * 时钟取容器里的 {@link Clock}（没有时为系统时钟）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "maccms.site", name = "enabled", havingValue = "true")
public class CommentConfiguration {

    @Bean
    InteractionSettingsSource interactionSettingsSource(ObjectProvider<LegacyConfigService> legacy) {
        return new InteractionSettingsSource(legacy.getIfAvailable());
    }

    @Bean
    CommentService commentService(CommentMapper reader, ObjectProvider<CommentWriteMapper> writer,
                                  InteractionSettingsSource settings, ObjectProvider<Clock> clock) {
        return new CommentService(reader, writer.getIfAvailable(), settings, clock.getIfAvailable(Clock::systemUTC));
    }
}
