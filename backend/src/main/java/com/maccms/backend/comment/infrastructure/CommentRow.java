package com.maccms.backend.comment.infrastructure;

/** mac_comment 一行（install.sql 的列顺序，CommentMapper.xml；B11）。内容与昵称是库里的原值（已经过旧版的两次转义）。 */
public record CommentRow(Long id, Integer mid, Long rid, Long pid, Long userId, Integer status, String name, Long ip,
                         Long time, String content, Long up, Long down, Long reply, Long report) {
}
