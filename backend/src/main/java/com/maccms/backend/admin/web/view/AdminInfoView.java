package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/**
 * 管理员表单页（旧 admin/view/admin/info.html 与 admin/controller/Admin.php:69-96 的权限树）。
 *
 * @param adminId 编辑时的 id，新增时为空串
 * @param enabled 旧模板 {@code $info['admin_status'] eq 1}（新增时没有这一行，"禁用"被选中，与旧版相同）
 */
public record AdminInfoView(AdminHead head, LegacyLang lang, String adminId, String adminName, boolean enabled,
                            String token, List<Group> groups) {

    /**
     * 权限树的一组。
     *
     * @param index   组的序号（从 1 开始，旧模板的 {@code k1}，复选框的 data-id / data-pid）
     * @param checked 组内每一项都按子串命中时为 true（旧 {@code $all == $cs}）
     */
    public record Group(int index, String name, boolean checked, List<Item> items) {
    }

    /**
     * 权限树的一项。
     *
     * @param checked  旧 {@code strpos(','.admin_auth, 控制器/动作) > 0}（子串命中，不要求前后有逗号）
     * @param readonly 条目键为 11 的项（只有"欢迎页面"，旧版强制勾选并只读）
     */
    public record Item(String code, String name, boolean checked, boolean readonly) {
    }
}
