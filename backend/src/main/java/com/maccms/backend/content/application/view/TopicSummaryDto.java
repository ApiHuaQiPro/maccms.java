package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/** 专题列表项（mac_topic 公开元数据）。字段与 OpenAPI schema TopicSummary 一一对应；不含正文、模板、关联 ID、扩展字段。 */
public record TopicSummaryDto(
        int id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        int sort,
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
