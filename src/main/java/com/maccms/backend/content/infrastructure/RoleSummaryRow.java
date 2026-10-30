package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/** mac_role 列表项的公开列（RoleMapper.xml）。videoId 为 role_rid；正文、模板、跳转、锁定等列不查询。 */
public record RoleSummaryRow(
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
        Integer scoreNum,
        Integer hits,
        Integer hitsDay,
        Integer hitsWeek,
        Integer hitsMonth,
        Integer up,
        Integer down,
        Long updatedAt,
        Long addedAt) {
}
