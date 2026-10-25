package com.maccms.backend.member.infrastructure;

/**
 * {@code mac_user} 一行中账号功能用到的列（B09）：资料页、改密校验与会员中心。{@code userPwd}、{@code userRandom} 只在 member 模块内
 * 使用，不进入任何视图类型。
 */
public record MemberAccountRow(long userId, int groupId, String userName, String userPwd, String userRandom,
                               int userStatus, long userEndTime, String nickName, String qq, String email, String phone,
                               String question, String answer, long points, long regTime, long loginTime,
                               String portrait) {
}
