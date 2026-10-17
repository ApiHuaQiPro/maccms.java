package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/**
 * 网址列表项（mac_website 公开元数据）。字段与 OpenAPI schema WebsiteSummary 一一对应；url 是网址本身（website_jumpurl），
 * 不含正文、模板、锁定等字段。
 */
public record WebsiteSummaryDto(
        long id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        CategoryRefDto category,
        int sort,
        String url,
        String pic,
        String logo,
        String area,
        String lang,
        String tag,
        String classes,
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
        int referer,
        int refererDay,
        int refererWeek,
        int refererMonth,
        long updatedAt,
        long addedAt) {
}
