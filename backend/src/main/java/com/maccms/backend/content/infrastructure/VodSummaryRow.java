package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/**
 * mac_vod 列表项的公开列。detailPasswordRequired 由 SQL 计算 CHAR_LENGTH(vod_pwd) &gt; 0，
 * 密码本身从不读入内存；播放/下载地址、积分、模板、会员组等列不查询。
 */
public record VodSummaryRow(
        Long id,
        Integer categoryId,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
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
        Integer total,
        Integer isEnd,
        Integer level,
        Integer copyright,
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
