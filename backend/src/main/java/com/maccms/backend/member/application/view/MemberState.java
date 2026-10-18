package com.maccms.backend.member.application.view;

/**
 * 一次请求的会员登录态（B08，17 文档第 2.2 节），对应旧 {@code label_user} 放进 {@code $GLOBALS['user']} 的结果。
 *
 * @param member         是否已登录；游客为 false，此时 {@code userId=0}、{@code groupId=1}
 * @param userId         {@code mac_user.user_id}
 * @param userName       库里的 {@code user_name} 原值
 * @param groupId        有效会员组：库里 {@code group_id > 2} 且 {@code user_end_time < 现在} 时按 2（内存判定，不写库）
 * @param storedGroupId  库里的 {@code group_id}
 * @param group          有效会员组的行；库里没有这个组时为 null（与旧版 {@code $group_list[id]} 取不到时相同）
 * @param userEndTime    {@code user_end_time}
 * @param expiredInMemory 本次按到期规则降为 2 组（旧版此时会写库并重发 {@code group_id}/{@code group_name} cookie，新版只在内存中判定）
 */
public record MemberState(boolean member, long userId, String userName, int groupId, int storedGroupId,
                          MemberGroup group, long userEndTime, boolean expiredInMemory) {

    /** 游客组（旧 {@code label_user} 的游客：{@code group_id=1}）。 */
    public static final int GUEST_GROUP_ID = 1;
    /** 到期后降入的组（旧 {@code User.php:363-365,455-466}）。 */
    public static final int EXPIRED_GROUP_ID = 2;

    public static MemberState guest(MemberGroup guestGroup) {
        return new MemberState(false, 0, "", GUEST_GROUP_ID, GUEST_GROUP_ID, guestGroup, 0, false);
    }
}
