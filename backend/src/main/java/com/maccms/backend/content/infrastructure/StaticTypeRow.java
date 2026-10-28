package com.maccms.backend.content.infrastructure;

/** 静态生成用的分类（{@code mac_type} 的几列，B16）。 */
public record StaticTypeRow(long id, String name, String en, long pid, int mid) {
    public StaticTypeRow {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
    }
}
