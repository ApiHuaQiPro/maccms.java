package com.maccms.backend.content.infrastructure;

/** 静态生成用的专题（B16）。 */
public record StaticTopicRow(long id, String name, String en) {
    public StaticTopicRow {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
    }
}
