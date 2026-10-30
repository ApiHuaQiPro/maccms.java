package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_website 详情的公开列（WebsiteMapper.xml）。screenshots 为 website_pic_screenshot 原文，由 content.application 解码。 */
public record WebsiteDetailRow(
        Long id,
        Integer categoryId,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        Integer sort,
        String url,
        String pic,
        String logo,
        String area,
        String lang,
        String tag,
        String classes,
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
        Integer referer,
        Integer refererDay,
        Integer refererWeek,
        Integer refererMonth,
        Long updatedAt,
        Long addedAt,
        String content,
        String screenshots) {
}
