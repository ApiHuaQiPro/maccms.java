package com.maccms.backend.member.infrastructure;

import java.math.BigDecimal;

/** mac_cash 一行（MemberFinanceMapper.xml）；撤销未审核提现的补偿按这些值原样插回。 */
public record CashRow(Long cashId, Long userId, Integer status, Integer points, BigDecimal money, String bankName,
                      String bankNo, String payeeName, Long time, Long timeAudit) {
}
