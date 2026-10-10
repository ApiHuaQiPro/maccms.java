package com.maccms.backend.content.api.dto;

import java.math.BigDecimal;

/**
 * 视频详情（公开元数据，不是播放授权）。字段与 OpenAPI schema VideoDetail 一一对应；
 * 不含观看密码、播放/下载地址、积分、会员组、模板等字段。content 是后台录入的原始 HTML。
 */
public record VideoDetailDto(
        long id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        CategoryRefDto category,
        String classes,
        String tag,
        String pic,
        String picThumb,
        String picSlide,
        String actor,
        String director,
        String writer,
        String behind,
        String blurb,
        String remarks,
        String pubdate,
        String area,
        String lang,
        String year,
        String version,
        String state,
        String tv,
        String weekday,
        String duration,
        String serial,
        int total,
        boolean finished,
        int level,
        boolean copyright,
        BigDecimal score,
        int scoreAll,
        int scoreNum,
        long doubanId,
        BigDecimal doubanScore,
        int hits,
        int hitsDay,
        int hitsWeek,
        int hitsMonth,
        int up,
        int down,
        long updatedAt,
        long addedAt,
        String content) {
}
