package com.maccms.backend.collect.application;

/** B17 后台采集配置管理的全部服务（25 文档第 2 节），由 admin 模块整体取用；只在可写数据源打开时注册。 */
public record CollectAdminServices(CollectSourceAdminService sources, CollectBindService binds, CjNodeAdminService nodes) {
}
