package com.maccms.backend.member.infrastructure;

/** mac_card 一行（MemberFinanceMapper.xml），不含卡密：卡密只作为 WHERE 条件使用。 */
public record CardRow(Long cardId, String cardNo, Integer money, Integer points, Integer useStatus, Integer saleStatus,
                      Long userId, Long addTime, Long useTime) {
}
