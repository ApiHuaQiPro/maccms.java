package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/** 演员公开详情。字段与 OpenAPI schema ActorDetail 一一对应。 */
public record ActorDetailDto(
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
