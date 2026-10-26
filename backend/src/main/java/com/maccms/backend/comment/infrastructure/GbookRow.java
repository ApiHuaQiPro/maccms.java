package com.maccms.backend.comment.infrastructure;

/** mac_gbook 一行（install.sql 的列顺序，GbookMapper.xml；B11）。 */
public record GbookRow(Long id, Long rid, Long userId, Integer status, String name, Long ip, Long time, Long replyTime,
                       String content, String reply) {
}
