package com.maccms.backend.content.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧表的一行（B06，16 文档第 3.5 节）："列名 → 值"，按旧表的列顺序（或 Provide 列表字段顺序）排列。值是 {@link Long}（整数列）、
 * {@link String}（DECIMAL 与文本列）或 {@code null}（旧版补出的 {@code type_name} 等）。不含访问密码列；受访问密码保护的正文/地址
 * 已置为空串（16 文档第 3.6 节）。不对应 OpenAPI schema（只供 openapi/site 按旧协议输出）。
 */
public record LegacyRowDto(Map<String, Object> fields) {

    public LegacyRowDto {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public Object get(String name) {
        return fields.get(name);
    }

    public String string(String name) {
        Object v = fields.get(name);
        return v == null ? "" : v.toString();
    }

    public long number(String name) {
        return fields.get(name) instanceof Long l ? l : 0L;
    }
}
