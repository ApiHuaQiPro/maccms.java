package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/**
 * mac_topic 详情的公开列（TopicMapper.xml）。relatedVideoIds/relatedArticleIds 是逗号分隔的原文（topic_rel_vod/topic_rel_art），
 * 只用于查询关联内容，不输出。
 */
public record TopicDetailRow(
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
        String content,
        String relatedVideoIds,
        String relatedArticleIds) {
}
