package com.maccms.backend.content.application.view;

import java.math.BigDecimal;

/**
 * 视频列表项（公开元数据）。字段与 OpenAPI schema VideoSummary 一一对应；
 * 不含观看密码、播放/下载地址、积分、会员组、模板等字段。
 */
public record VideoSummaryDto(
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
        String blurb,
        String remarks,
        String area,
        String lang,
        String year,
        String version,
        String state,
        String serial,
        int total,
        boolean finished,
        int level,
        boolean copyright,
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
