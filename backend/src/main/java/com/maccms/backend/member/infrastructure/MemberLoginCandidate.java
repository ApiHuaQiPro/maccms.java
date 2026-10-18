package com.maccms.backend.member.infrastructure;

/** 旧 {@code login()} 查到的会员（{@code common/model/User.php:356-357}）。 */
public record MemberLoginCandidate(long userId, int groupId, String userName, long userEndTime) {
}
