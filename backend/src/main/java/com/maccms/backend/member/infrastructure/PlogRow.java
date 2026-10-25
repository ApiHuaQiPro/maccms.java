package com.maccms.backend.member.infrastructure;

/** mac_plog 一行（MemberFinanceMapper.xml）。 */
public record PlogRow(Long plogId, Long userId, Integer userId1, Integer type, Integer points, Long time,
                      String remarks) {
}
