package com.maccms.backend.site.web.view;

/**
 * 搜索表单的隐藏字段：名字是固定的旧参数名，值是已校验的条件（按属性转义输出）。
 */
public record HiddenField(String name, String value) {
}
