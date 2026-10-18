package com.maccms.backend.member.infrastructure;

/** {@code mac_user} 中登录态校验用到的列。 */
public record MemberLoginRow(long userId, int groupId, String userName, String userRandom, long userEndTime) {
}
