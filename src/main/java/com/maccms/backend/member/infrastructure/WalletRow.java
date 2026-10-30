package com.maccms.backend.member.infrastructure;

/** 会员的积分、冻结积分、会员组、到期时间与推广关系（MemberFinanceMapper.xml）。 */
public record WalletRow(Long userId, String userName, Integer groupId, Long points, Long froze, Long endTime, Long pid,
                        Long pid2, Long pid3) {
}
