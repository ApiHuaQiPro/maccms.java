package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminTypeNode;
import java.util.List;

/**
 * 批量操作的弹出表单（旧 Index::select → admin/view/public/select_*.html，没有页头页脚，由 j-select 以 Ajax 取回后放进弹层）。
 *
 * @param tpl     模板名（只接受旧版的 7 个 select_* 模板）
 * @param action  表单提交地址（旧 {@code url($url)}）
 * @param ids     编号（数组时以逗号连接）
 * @param refresh 提交后是否刷新（旧版为空时 {@code yes}）
 * @param mid     分类模板的模型（tab 为 art 时 2、actor 8、website 11，其余 1）
 * @param types   分类树（只有 select_type 用）
 */
public record SelectView(LegacyLang lang, String tpl, String action, String col, String ids, String val, String refresh,
                         int mid, List<AdminTypeNode> types) {

    /** 分类模板里子分类的选中（旧 {@code $ch.type_id eq $val}）。 */
    public boolean selected(AdminTypeNode ch) {
        return TypeInfoView.looseEqStr(ch.row().s("type_id"), val);
    }
}
