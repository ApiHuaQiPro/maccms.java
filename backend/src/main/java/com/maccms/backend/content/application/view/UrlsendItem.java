package com.maccms.backend.content.application.view;

/** 后台 URL 推送的一行（B28）：生成详情页地址要用的编号、拼音、更新时间与分类，以及列表里显示的名称。 */
public record UrlsendItem(long id, String name, String en, long time, long typeId) {
}
