package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_actor 详情的公开列（ActorMapper.xml）。 */
public record ActorDetailRow(
        Long id,
        Integer categoryId,
        String name,
        String enName,
        String alias,
        String letter,
        String color,
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
        Integer level,
        BigDecimal score,
        Integer scoreAll,
        Integer scoreNum,
        Integer hits,
        Integer hitsDay,
        Integer hitsWeek,
        Integer hitsMonth,
        Integer up,
        Integer down,
        Long updatedAt,
        Long addedAt,
        String content) {
}
