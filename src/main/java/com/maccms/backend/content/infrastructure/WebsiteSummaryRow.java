package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_website 列表项的公开列（WebsiteMapper.xml）。url 为 website_jumpurl；正文、模板、锁定等列不查询。 */
public record WebsiteSummaryRow(
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
        Long addedAt) {
}
