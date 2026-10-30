package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/**
 * 文章列表项（mac_art 公开元数据）。字段与 OpenAPI schema ArticleSummary 一一对应；
 * 不含正文、访问密码、积分、会员组、模板、跳转、关联等字段。
 */
public record ArticleSummaryDto(
        long id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        CategoryRefDto category,
        String source,
        String author,
        String tag,
        String classes,
        String pic,
        String picThumb,
        String picSlide,
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
        long addedAt,
        boolean detailPasswordRequired) {
}
