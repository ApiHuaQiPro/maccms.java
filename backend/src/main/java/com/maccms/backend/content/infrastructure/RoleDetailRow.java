package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_role 详情的公开列（RoleMapper.xml）。videoId 为 role_rid。 */
public record RoleDetailRow(
        Long id,
        Long videoId,
        String name,
        String enName,
        String letter,
        String color,
        String actor,
        String remarks,
        String pic,
        Integer sort,
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
        String content) {
}
