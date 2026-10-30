package com.maccms.backend.content.application.view;

/** 文章的一页（mac_art_list，application/common.php:1294-1318）：page 从 1 起，title/note 按位置对齐，缺少时为 ""。 */
public record ArticleContentPageDto(int page, String title, String note, String content) {
}
