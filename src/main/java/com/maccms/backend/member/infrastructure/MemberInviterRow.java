package com.maccms.backend.member.infrastructure;

/** 推广注册的邀请人（{@code common/model/User.php:238-247}）。 */
public record MemberInviterRow(long userId, long pid, long pid2) {
}
