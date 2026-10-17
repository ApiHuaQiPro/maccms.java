package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_actor 列表项的公开列（ActorMapper.xml）。正文、模板、跳转、锁定等列不查询。 */
public record ActorSummaryRow(
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
        Integer scoreNum,
        Integer hits,
        Integer hitsDay,
        Integer hitsWeek,
        Integer hitsMonth,
        Integer up,
        Integer down,
        Long updatedAt,
        Long addedAt) {
}
