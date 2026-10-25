package com.maccms.backend.content.application.view;

/** 网址来路计数（旧 {@code ajax/referer} 的 {@code data}，application/index/controller/Ajax.php:288-297；B11）。 */
public record RefererCounts(long referer, long refererDay, long refererWeek, long refererMonth) {
}
