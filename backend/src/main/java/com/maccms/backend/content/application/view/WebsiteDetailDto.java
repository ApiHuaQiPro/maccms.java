package com.maccms.backend.content.application.view;

import java.math.BigDecimal;
import java.util.List;

/** 网址公开详情。字段与 OpenAPI schema WebsiteDetail 一一对应；screenshots 由 LegacyMultiValue 解码。 */
public record WebsiteDetailDto(
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
        int scoreAll,
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
        long addedAt,
        String content,
        List<ScreenshotDto> screenshots) {
}
