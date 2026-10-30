package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.RawOutput;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;

/** 采集源表单（旧 admin/view/collect/info.html）；新增时 {@code info} 为空行。 */
public record CollectInfoView(AdminHead head, LegacyLang lang, AdminRow info, String token, String testUrl) implements RawOutput {

    /**
     * 旧模板的 {@code {if condition="$info['列'] eq 数"}}（PHP 宽松比较）：新增时 {@code $info} 为 null，{@code null == 0} 为真、与其他数比较为假；
     * 有这一行时列是十进制数字的文字，按数值比较。
     */
    public boolean eq(String col, long n) {
        return info.has(col) ? info.n(col) == n : n == 0;
    }

    /** 过滤地址等两行：{@code $info['collect_mid'] neq '1'} 时隐藏（新增时隐藏）。 */
    public boolean hideFilter() {
        return !(info.has("collect_mid") && info.n("collect_mid") == 1);
    }
}
