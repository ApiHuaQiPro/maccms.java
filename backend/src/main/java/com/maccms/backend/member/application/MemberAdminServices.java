package com.maccms.backend.member.application;

/**
 * B14 后台会员与财务管理的服务（23 文档第 2 节），只供 admin 模块调用；可写数据源打开时注册（{@link MemberAdminConfiguration}）。
 */
public record MemberAdminServices(UserAdminService users, GroupAdminService groups, CardAdminService cards,
                                  FinanceAdminService finance, MemberLogAdminService logs, AdminPortraitService portraits) {
}
