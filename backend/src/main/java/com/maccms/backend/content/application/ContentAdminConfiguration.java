package com.maccms.backend.content.application;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import org.springframework.beans.factory.annotation.Value;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B13 后台内容管理的服务（22 文档第 2 节）：只供 admin 模块调用（ModuleBoundaryTest 锁定）。后台本身要求可写数据源打开，所以这些服务也只在
 * 可写数据源打开时注册。时钟取容器里的 {@link Clock}（没有时为系统时钟；黄金复放测试把"现在"固定到 PHP 请求的时刻）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWriteEnabled
public class ContentAdminConfiguration {

    @Bean
    AdminConfig adminContentConfig(ObjectProvider<LegacyConfigService> legacy) {
        return new AdminConfig(legacy.getIfAvailable());
    }

    /** 旧站 upload/ 目录（部署参数 maccms.upload.dir，为空时删除内容不删文件、上传不可用）。 */
    @Bean
    UploadStore adminUploadStore(@Value("${maccms.upload.dir:}") String dir) {
        return new UploadStore(dir);
    }

    @Bean
    LinkAdminService linkAdminService(ContentAdminMapper reader, ContentAdminWrites writer, ObjectProvider<Clock> clock) {
        return new LinkAdminService(reader, writer, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    TypeAdminService typeAdminService(ContentAdminMapper reader, ContentAdminWrites writer, AdminConfig config) {
        return new TypeAdminService(reader, writer, config);
    }

    @Bean
    TopicAdminService topicAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads,
                                        AdminConfig config, ObjectProvider<Clock> clock) {
        return new TopicAdminService(reader, writer, uploads, config, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ActorAdminService actorAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads,
                                        AdminConfig config, TypeAdminService types, ObjectProvider<Clock> clock) {
        return new ActorAdminService(reader, writer, uploads, config, types, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    RoleAdminService roleAdminService(ContentAdminMapper reader, ContentAdminWrites writer, AdminConfig config,
                                      ObjectProvider<Clock> clock) {
        return new RoleAdminService(reader, writer, config, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    WebsiteAdminService websiteAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads,
                                            AdminConfig config, TypeAdminService types, ObjectProvider<Clock> clock) {
        return new WebsiteAdminService(reader, writer, uploads, config, types, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ArtAdminService artAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads,
                                    AdminConfig config, TypeAdminService types, ObjectProvider<Clock> clock) {
        return new ArtAdminService(reader, writer, uploads, config, types, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    AdminPlayers adminPlayers(ObjectProvider<LegacyConfigService> legacy) {
        return new AdminPlayers(legacy.getIfAvailable());
    }

    @Bean
    VodAdminService vodAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads,
                                    AdminConfig config, TypeAdminService types, VodSearchCache search,
                                    VodSearchOptimise optimise, ObjectProvider<Clock> clock) {
        return new VodAdminService(reader, writer, uploads, config, types, search, optimise,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ContentAdminServices contentAdminServices(AdminConfig config, AdminPlayers players, LinkAdminService links,
                                              TypeAdminService types, TopicAdminService topics, ActorAdminService actors,
                                              RoleAdminService roles, WebsiteAdminService websites, ArtAdminService arts,
                                              VodAdminService vods) {
        return new ContentAdminServices(config, players, links, types, topics, actors, roles, websites, arts, vods);
    }
}
