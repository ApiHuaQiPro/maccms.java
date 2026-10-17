package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/** 角色列表项（mac_role 公开元数据）。字段与 OpenAPI schema RoleSummary 一一对应；不含正文、模板、跳转、锁定等字段。 */
public record RoleSummaryDto(
        long id,
        RoleVideoDto video,
        String name,
        String enName,
        String letter,
        String color,
        String actor,
        String remarks,
        String pic,
        int sort,
        int level,
        BigDecimal score,
        int scoreNum,
        int hits,
        int hitsDay,
        int hitsWeek,
        int hitsMonth,
        int up,
        int down,
        long updatedAt,
        long addedAt) {
}
