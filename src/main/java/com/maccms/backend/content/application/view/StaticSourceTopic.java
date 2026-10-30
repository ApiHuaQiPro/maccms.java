package com.maccms.backend.content.application.view;

/** 静态生成用的专题（B16）。 */
public record StaticSourceTopic(long id, String name, String en) {
    public StaticSourceTopic {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
    }
}
