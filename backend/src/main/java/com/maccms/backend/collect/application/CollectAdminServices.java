package com.maccms.backend.collect.application;

/**
 * 后台采集的全部服务，由 admin 模块整体取用；只在可写数据源打开时注册。B17 采集配置管理（25 文档第 2 节）：{@code sources}、{@code binds}、
 * {@code nodes}；B18 本地文件源的解析与预览（26 文档第 2 节）：{@code preview}。
 */
public record CollectAdminServices(CollectSourceAdminService sources, CollectBindService binds, CjNodeAdminService nodes,
                                   CollectPreviewService preview) {
}
