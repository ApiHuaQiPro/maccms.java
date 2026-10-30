package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 提现页（{@code user/cash}，B10，19 文档第 4.9 节）：积分、冻结积分与规则，提现开启时给出申请表单，下方是提现记录（每行可撤销）。
 *
 * @param shell   会员页外壳
 * @param summary 可用积分、冻结积分、每元积分、最低提现金额
 * @param action  申请表单的提交地址（{@code user/cash}）；提现未开启时为空
 * @param selfUrl 本页（申请或撤销成功后刷新）
 * @param table   提现记录
 * @param back    返回会员中心
 */
public record UserCashPage(MemberShell shell, List<MetadataItem> summary, Optional<SafeUrl> action, SafeUrl selfUrl,
                           UserTable table, NavLink back) {

    public UserCashPage {
        summary = List.copyOf(summary);
    }
}
