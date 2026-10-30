package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/** 演员列表项（mac_actor 公开元数据）。字段与 OpenAPI schema ActorSummary 一一对应；不含正文、模板、跳转、锁定等字段。 */
public record ActorSummaryDto(
        long id,
        String name,
        String enName,
        String alias,
        String letter,
        String color,
        CategoryRefDto category,
        String sex,
        String pic,
        String blurb,
        String remarks,
        String area,
        String height,
        String weight,
        String birthday,
        String birthArea,
        String blood,
        String starSign,
        String school,
        String works,
        String tag,
        String classes,
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
