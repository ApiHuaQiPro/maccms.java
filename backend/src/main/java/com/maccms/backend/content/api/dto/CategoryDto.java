package com.maccms.backend.content.api.dto;

import java.util.List;

/** 视频分类树节点。字段与 OpenAPI schema Category 一一对应。 */
public record CategoryDto(
        int id,
        String name,
        String enName,
        int sort,
        int parentId,
        String title,
        String keywords,
        String description,
        String logo,
        String pic,
        CategoryExtendDto extend,
        List<CategoryDto> children) {
}
