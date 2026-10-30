package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/**
 * 导航、面包屑、分类网格中的一项。
 *
 * @param label   显示文字（已按字段规则解码一次字符引用，由模板按文本转义）
 * @param url     链接（站内路径，由 LegacyUrlBuilder 生成）
 * @param current 是否就是当前页（模板输出 aria-current="page"）
 */
public record NavLink(String label, SafeUrl url, boolean current) {
}
