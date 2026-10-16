package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 筛选/排序中的一个链接（普通 a，无 JS 可用）。
 *
 * @param label   显示文字（按文本转义）
 * @param url     链接（固定路径 + 查询串，由 LegacyUrlBuilder.vodSearchLink 生成）
 * @param current 是否就是当前条件（模板输出 aria-current="true"）
 */
public record FilterChip(String label, SafeUrl url, boolean current) {
}
