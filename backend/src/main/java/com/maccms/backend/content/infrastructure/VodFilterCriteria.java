package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * VodMapper.xml 中 {@code matchingConditions} 片段的绑定值，由 content.application 用已校验的条件构造。
 * 只作为 {@code #{}} 参数绑定，列名固定写在 XML 中；字段为 {@code null} 表示该条件不参与。
 *
 * @param level           vod_level 精确匹配
 * @param year            vod_year 精确匹配（字符串比较）
 * @param area            vod_area 精确匹配
 * @param lang            vod_lang 精确匹配
 * @param classesPattern  vod_class 的 LIKE 模式（已按 ESCAPE '!' 转义并加首尾 %）
 * @param keywordPatterns vod_name/vod_sub/vod_en 的 LIKE 模式（1 或 2 项，项之间 OR）
 */
public record VodFilterCriteria(Integer level, String year, String area, String lang, String classesPattern,
                                List<String> keywordPatterns) {

    public VodFilterCriteria {
        if (keywordPatterns != null) {
            keywordPatterns = List.copyOf(keywordPatterns);
            if (keywordPatterns.isEmpty() || keywordPatterns.size() > 2) {
                throw new IllegalArgumentException("keywordPatterns 只能有 1 或 2 项");
            }
        }
        if (level == null && year == null && area == null && lang == null && classesPattern == null
                && keywordPatterns == null) {
            throw new IllegalArgumentException("没有任何条件时应使用不带条件的查询");
        }
    }
}
