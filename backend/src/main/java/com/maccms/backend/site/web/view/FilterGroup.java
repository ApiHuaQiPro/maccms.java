package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 搜索页的一组筛选或排序链接。
 *
 * @param key   组标识（旧参数名 area、lang、year、class 或 by），只用于元素 id
 * @param label 组名（地区、语言……）
 * @param chips 链接，第一项通常是"全部"（去掉该条件）
 */
public record FilterGroup(String key, String label, List<FilterChip> chips) {
}
