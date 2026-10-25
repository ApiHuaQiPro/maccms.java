package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 升级会员组页（{@code user/upgrade}，B10，19 文档第 4.7 节）：会员组（{@code group_id ≥ 3} 且启用）单选 + 时长单选，成功后刷新本页。
 *
 * @param shell   会员页外壳
 * @param summary 当前会员组、到期时间与积分
 * @param groups  可选的会员组与价格
 * @param action  提交地址（{@code user/upgrade}）
 * @param selfUrl 本页
 * @param back    返回会员中心
 */
public record UserUpgradePage(MemberShell shell, List<MetadataItem> summary, List<Option> groups, SafeUrl action,
                              SafeUrl selfUrl, NavLink back) {

    public UserUpgradePage {
        summary = List.copyOf(summary);
        groups = List.copyOf(groups);
    }

    /** 一个会员组与四种时长的积分价格。 */
    public record Option(int id, String name, int day, int week, int month, int year) {
    }
}
