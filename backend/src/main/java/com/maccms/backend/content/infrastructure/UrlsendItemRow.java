package com.maccms.backend.content.infrastructure;

/** 后台 URL 推送的一行（B28）：编号、名称、拼音、更新时间与分类（专题、角色没有分类，为 0）。 */
public record UrlsendItemRow(long id, String name, String en, long time, long typeId) {
    public UrlsendItemRow {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
    }
}
