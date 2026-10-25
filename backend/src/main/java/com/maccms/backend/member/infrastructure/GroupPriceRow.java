package com.maccms.backend.member.infrastructure;

/** mac_group 一行的名称、状态、权限与四种时长的积分价格（MemberFinanceMapper.xml）。 */
public record GroupPriceRow(Integer id, String name, Integer status, String types, String popedom, Integer day,
                            Integer week, Integer month, Integer year) {
}
