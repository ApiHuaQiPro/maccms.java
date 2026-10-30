package com.maccms.backend.content.infrastructure;

import java.util.Map;

/** 新增一行的参数与取回的自增主键（MyBatis useGeneratedKeys 写回 {@link #id}）。 */
public final class AdminInsert {

    private final Map<String, String> c;
    private Long id;

    public AdminInsert(Map<String, String> columns) {
        this.c = Map.copyOf(columns);
    }

    public Map<String, String> getC() {
        return c;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
