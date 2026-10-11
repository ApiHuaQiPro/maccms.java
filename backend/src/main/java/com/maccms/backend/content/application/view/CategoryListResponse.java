package com.maccms.backend.content.application.view;

import java.util.List;

/** GET /api/v1/video-categories 的响应：顶级分类数组，子分类在 children 中。 */
public record CategoryListResponse(List<CategoryDto> items) {
}
