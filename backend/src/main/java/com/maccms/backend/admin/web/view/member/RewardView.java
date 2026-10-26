package com.maccms.backend.admin.web.view.member;

import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.member.application.view.MemberRewardStats;

/** 推广会员页（旧 admin/view/user/reward.html）：列表部分是 {@link MemberListView}，这里是三级人数与奖励积分合计。 */
public record RewardView(MemberRewardStats stats) {

    /** 分销等级（旧 {@code $vo['user_pid'] eq $param['uid']} 一级，{@code user_pid_2} 二级，否则三级）。 */
    public int level(AdminRow vo) {
        if (vo.n("user_pid") == stats.uid()) {
            return 1;
        }
        return vo.n("user_pid_2") == stats.uid() ? 2 : 3;
    }
}
