package com.maccms.backend.admin.web.view.member;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.RawOutput;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminTypeNode;
import com.maccms.backend.member.application.GroupAdminService;
import java.util.List;

/**
 * 会员组表单（旧 admin/view/group/info.html）；新增时 {@code info} 为空行。
 *
 * @param types 分类树（旧 {@code Type::getCache('type_tree')}，含停用分类）
 */
public record GroupInfoView(AdminHead head, LegacyLang lang, AdminRow info, List<AdminTypeNode> types) implements RawOutput {

    /**
     * 权限表的一行：一级分类与其下的二级分类依次排列（旧模板两层 {@code volist}），{@code k1} 为所属一级分类的序号（从 1 开始，复选框的
     * {@code data-id}），二级分类的名称前加 {@code ---}。
     */
    public record TypeEntry(int k1, String prefix, AdminRow row) {
    }

    public List<TypeEntry> entries() {
        List<TypeEntry> out = new java.util.ArrayList<>();
        int k1 = 0;
        for (AdminTypeNode t : types) {
            k1++;
            out.add(new TypeEntry(k1, "", t.row()));
            for (AdminTypeNode c : t.children()) {
                out.add(new TypeEntry(k1, "---", c.row()));
            }
        }
        return out;
    }

    /** 旧 {@code $info.group_id gt 2}：自定义会员组才有价格与状态。 */
    public boolean custom() {
        return info.has("group_id") && info.n("group_id") > 2;
    }

    /** 状态"启用"（旧 {@code $info['group_status'] eq 1}）。 */
    public boolean enabled() {
        return info.has("group_status") && info.n("group_status") == 1;
    }

    /** 分类复选框（旧 {@code strpos(','.$info['group_type'], ','.$vo['type_id'].',') > 0}）。 */
    public boolean typeChecked(AdminRow type) {
        return (',' + info.s("group_type")).indexOf(',' + type.s("type_id") + ',') > 0;
    }

    /** 权限复选框（旧 {@code !empty($info['group_popedom'][分类][位])}，{@code group_popedom} 为 {@code json_decode(…, true)}）。 */
    public boolean bit(AdminRow type, int bit) {
        return GroupAdminService.popedomChecked(PhpJson.decode(info.s("group_popedom")), type.s("type_id"), Integer.toString(bit));
    }
}
