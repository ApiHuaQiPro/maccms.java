package com.maccms.backend.content.application.view;

/** 静态生成用的分类（{@code mac_type} 的几列，B16）。 */
public record StaticSourceType(long id, String name, String en, long pid, int mid) {
    public StaticSourceType {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
    }
}
