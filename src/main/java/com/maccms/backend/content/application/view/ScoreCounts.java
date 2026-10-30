package com.maccms.backend.content.application.view;

/**
 * 评分（旧 {@code ajax/score} 的 {@code data}，application/index/controller/Ajax.php:381-391；B11）。
 *
 * @param score 平均分，一位小数的字符串（旧版评分后为 {@code number_format} 的结果、只读时为库里 decimal 的字符串，两者形式相同，如 {@code "7.3"}）
 */
public record ScoreCounts(String score, long scoreNum, long scoreAll) {
}
