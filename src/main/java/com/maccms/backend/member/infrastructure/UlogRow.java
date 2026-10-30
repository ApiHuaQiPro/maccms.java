package com.maccms.backend.member.infrastructure;

/** mac_ulog 一行（install.sql 的列顺序；MemberFinanceMapper.xml）。 */
public record UlogRow(Long ulogId, Long userId, Integer mid, Integer type, Long rid, Integer sid, Integer nid,
                      Integer points, Long time) {
}
