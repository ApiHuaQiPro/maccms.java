package com.maccms.backend.member.application.view;

import com.maccms.backend.content.application.view.AdminRowPage;

/**
 * 后台推广会员页（B14，旧 User::reward）：推广会员的一页与三级人数、奖励积分合计（{@code plog_type} 4/5/6）。
 *
 * @param uid 推广人（旧 {@code intval($param['uid'])}）
 */
public record MemberRewardStats(AdminRowPage list, long uid, long level1, long level2, long level3, long points1,
                                long points2, long points3) {
}
