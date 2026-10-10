package com.maccms.backend.content.api.dto;

/**
 * mac_type.type_extend（JSON）中用于前台筛选的候选值，均为逗号分隔的原始字符串；缺失或无法解析时为空字符串。
 * classes 对应 JSON 键 class（旧 common/model/Type.php:52,222-226）。
 */
public record CategoryExtendDto(
        String classes,
        String area,
        String lang,
        String year,
        String star,
        String director,
        String state,
        String version) {
}
