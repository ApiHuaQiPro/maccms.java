package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import java.util.List;
import java.util.Map;

/**
 * 分类表单（旧 admin/view/type/info.html）。
 *
 * @param info    编辑的分类（新增时为空行）
 * @param infop   {@code input('pid')} 对应的分类（没有时为空行）
 * @param pid     {@code input('pid')} 的原值（旧模板 {@code $pid eq $vo.type_id} 比较）
 * @param extend  扩展字典的显示值（{@code $info.type_extend.*}）
 * @param token   表单令牌
 */
public record TypeInfoView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRow info, AdminRow infop, String pid,
                           List<AdminRow> parents, Map<String, String> extend, String token) implements RawOutput {

    /** 类型下拉框的选中项（旧 {@code $info['type_mid'] == 'n' || ($info.type_id eq 0 && $infop['type_mid'] == 'n')}）。 */
    public boolean midSelected(int mid) {
        return looseEq(info.s("type_mid"), mid) || (looseEqZero(info.s("type_id")) && looseEq(infop.s("type_mid"), mid));
    }

    /** 旧 {@code $info.type_pid eq $vo.type_id || $pid eq $vo.type_id}。 */
    public boolean parentSelected(AdminRow vo) {
        return looseEqStr(info.s("type_pid"), vo.s("type_id")) || looseEqStr(pid, vo.s("type_id"));
    }

    /** 状态单选（旧 {@code $info['type_status'] neq 1} / {@code eq 1}）。 */
    public boolean enabled() {
        return looseEq(info.s("type_status"), 1);
    }

    /** 视频专用的扩展项隐藏（旧 {@code $info.type_mid neq '1'}）。 */
    public boolean vodHidden() {
        return !looseEq(info.s("type_mid"), 1);
    }

    public String ext(String key) {
        String v = extend.get(key);
        return v == null ? "" : v;
    }

    /** {@code $v == n}（模板变量是字符串或 null；null/空串只等于 0）。 */
    static boolean looseEq(String v, long n) {
        if (v == null || v.isEmpty()) {
            return n == 0;
        }
        try {
            return Double.parseDouble(v.strip()) == n;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean looseEqZero(String v) {
        return looseEq(v, 0);
    }

    /** 两个模板变量的 {@code eq}：都是数字串时按数值，否则按文字。 */
    static boolean looseEqStr(String a, String b) {
        try {
            return Double.parseDouble(a.strip()) == Double.parseDouble(b.strip());
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }
}
