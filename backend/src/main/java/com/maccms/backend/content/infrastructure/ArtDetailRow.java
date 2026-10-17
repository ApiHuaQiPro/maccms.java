package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/**
 * mac_art 详情的公开列（ArtMapper.xml）。pageTitles/pageNotes/content 是 {@code $$$} 分隔的分页原文（art_title/art_note/art_content），
 * 由 content.application 解码；detailPasswordRequired、pointsRequired 只以 SQL 计算的布尔值出现，密码与积分值不读入内存。
 */
public record ArtDetailRow(
        Long id,
        Integer categoryId,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        String source,
        String author,
        String tag,
        String classes,
        String pic,
        String picThumb,
        String picSlide,
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
        String pageTitles,
        String pageNotes,
        String content,
        String screenshots,
        Integer detailPasswordRequired,
        Integer pointsRequired) {
}
