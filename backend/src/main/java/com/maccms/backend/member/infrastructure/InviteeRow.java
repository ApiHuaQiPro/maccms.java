package com.maccms.backend.member.infrastructure;

/** 推广会员列表的一行（MemberFinanceMapper.xml）：只读用户名、昵称与注册、登录时间。 */
public record InviteeRow(Long userId, String userName, String nickName, Long regTime, Long loginTime) {
}
