package com.maccms.backend.content.application.view;

/** 友情链接（mac_link）。字段与 OpenAPI schema Link 一一对应；type 0 文字链接、1 图片链接（application/common/model/Link.php:47-49）。 */
public record LinkDto(int id, int type, String name, int sort, String url, String logo, long addedAt, long updatedAt) {
}
