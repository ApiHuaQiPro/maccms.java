package com.maccms.backend.member.infrastructure;

import java.math.BigDecimal;

/** mac_order 一行（MemberFinanceMapper.xml）。 */
public record OrderRow(Long orderId, Long userId, Integer status, String code, BigDecimal price, Long time,
                       Integer points, String payType, Long payTime, String remarks) {
}
