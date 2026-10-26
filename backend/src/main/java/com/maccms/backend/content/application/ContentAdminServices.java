package com.maccms.backend.content.application;

/**
 * B13 后台内容管理的全部服务（22 文档第 2 节），由 admin 模块整体取用；只在可写数据源打开时注册。
 */
public record ContentAdminServices(AdminConfig config, AdminPlayers players, LinkAdminService links,
                                   TypeAdminService types, TopicAdminService topics, ActorAdminService actors,
                                   RoleAdminService roles, WebsiteAdminService websites, ArtAdminService arts,
                                   VodAdminService vods, AnnexAdminService annexes, AdminUploadService uploads) {
}
