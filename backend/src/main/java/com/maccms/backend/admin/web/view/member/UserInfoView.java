package com.maccms.backend.admin.web.view.member;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.RawOutput;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import java.util.List;

/**
 * 会员表单（旧 admin/view/user/info.html）；新增时 {@code info} 为空行。密码框总为空（旧 {@code infoData} 把密码置空）。
 *
 * @param groups    全部会员组（旧 {@code Group::listData([], 'group_id asc')}）
 * @param uploadUrl 头像上传地址（旧 {@code url('upload/upload')?flag=user&user_id=…}；新版后台上传拒绝 {@code flag=user}，见 23 文档第 8.2 节）
 */
public record UserInfoView(AdminHead head, LegacyLang lang, AdminRow info, List<AdminRow> groups, String uploadUrl)
        implements RawOutput {

    /** 旧 {@code {if condition="$info.user_id neq ''"}}：编辑已有会员时才显示头像一栏。 */
    public boolean editing() {
        return !info.s("user_id").isEmpty();
    }

    /** 旧 {@code $info.group_id elt 2}：到期时间一栏隐藏（新增时组号为空，同样隐藏）。 */
    public boolean hideEndTime() {
        return !info.has("group_id") || info.n("group_id") <= 2;
    }

    /** 旧 {@code {$info.user_end_time|date='Y-m-d H:i:s',###}}（新增时为 0 点的时间 1970-01-01 08:00:00）。 */
    public String endTime() {
        return MemberListView.dateTime(info.n("user_end_time"));
    }

    /** 状态"启用"（旧 {@code $info['user_status'] eq 1}）。 */
    public boolean enabled() {
        return info.has("user_status") && info.n("user_status") == 1;
    }

    /** 会员组下拉框的选中项（旧 {@code $info['group_id'] eq $vo['group_id']}）。 */
    public boolean groupSelected(AdminRow g) {
        return info.has("group_id") && info.n("group_id") == g.n("group_id");
    }
}
