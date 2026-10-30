package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/** 角色公开详情。字段与 OpenAPI schema RoleDetail 一一对应。 */
public record RoleDetailDto(
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
        int scoreAll,
        int scoreNum,
        int hits,
        int hitsDay,
        int hitsWeek,
        int hitsMonth,
        int up,
        int down,
        long updatedAt,
        long addedAt,
        String content) {
}
