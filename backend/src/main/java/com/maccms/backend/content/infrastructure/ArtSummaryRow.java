package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/**
 * mac_art 列表项的公开列（ArtMapper.xml）。detailPasswordRequired 由 SQL 计算 CHAR_LENGTH(art_pwd) &gt; 0，
 * 密码本身从不读入内存；正文、积分、模板、跳转、关联、会员组等列不查询。
 */
public record ArtSummaryRow(
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
        Integer scoreNum,
        Integer hits,
        Integer hitsDay,
        Integer hitsWeek,
        Integer hitsMonth,
        Integer up,
        Integer down,
        Long updatedAt,
        Long addedAt,
        Integer detailPasswordRequired) {
}
