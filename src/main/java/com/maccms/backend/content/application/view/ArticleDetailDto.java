package com.maccms.backend.content.application.view;

import java.math.BigDecimal;
import java.util.List;

/** 文章公开详情。字段与 OpenAPI schema ArticleDetail 一一对应；pages、screenshots 由 LegacyMultiValue 解码。 */
public record ArticleDetailDto(
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
        int scoreAll,
        int scoreNum,
        int hits,
        int hitsDay,
        int hitsWeek,
        int hitsMonth,
        int up,
        int down,
        long updatedAt,
        long addedAt,
        List<ArticleContentPageDto> pages,
        List<ScreenshotDto> screenshots) {
}
