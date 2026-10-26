package com.maccms.backend.member.infrastructure;

import java.util.Map;

/** 新增一行（后台新增会员、会员组）的参数与取回的自增主键（MyBatis useGeneratedKeys 写回 {@link #id}）。 */
public final class MemberAdminInsert {

    private final Map<String, String> c;
    private Long id;

    public MemberAdminInsert(Map<String, String> columns) {
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
