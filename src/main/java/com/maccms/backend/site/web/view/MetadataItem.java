package com.maccms.backend.site.web.view;

/** 详情页元数据表的一行（dt/dd），值为纯文本，由模板转义；空值的行不出现在页面模型里。 */
public record MetadataItem(String label, String value) {
}
