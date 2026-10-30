package com.maccms.backend.content.application.view;

/** 点击计数（旧 {@code ajax/hits} 的 {@code data}，application/index/controller/Ajax.php:205-214；B11）。 */
public record HitCounts(long hits, long hitsDay, long hitsWeek, long hitsMonth) {
}
