package com.maccms.backend.content.application.view;

/**
 * 按编号查到的内容（B10，会员记录列表的 {@code data}，旧 {@code common/model/Ulog.php:36-100}）。不是 OpenAPI 的一部分。
 *
 * @param pic       图片字段原文（由 site 按 {@code mac_url_img} 规范化）
 * @param typeFound 有分类（视频、文章且分类存在）；否则 {@code typeId}、{@code typeName} 无意义
 */
public record ContentTitle(long id, String name, String pic, boolean typeFound, int typeId, String typeName) {
}
