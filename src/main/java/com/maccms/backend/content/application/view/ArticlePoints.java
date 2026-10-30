package com.maccms.backend.content.application.view;

/** 文章的积分列（B10，旧 {@code art_points} 整篇、{@code art_points_detail} 按页）。不是 OpenAPI 的一部分。 */
public record ArticlePoints(long id, int whole, int detail) {
}
