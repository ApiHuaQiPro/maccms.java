package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_topic 列表项的公开列（TopicMapper.xml）。正文、模板、关联 ID、扩展字段不查询。 */
public record TopicSummaryRow(
        Integer id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        Integer sort,
        String classes,
        String tag,
        String pic,
        String picThumb,
        String picSlide,
        String title,
        String keywords,
        String description,
        String blurb,
        String remarks,
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
