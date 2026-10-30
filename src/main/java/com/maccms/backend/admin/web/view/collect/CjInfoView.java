package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.RawOutput;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import java.util.List;
import java.util.Map;

/**
 * 自定义规则节点表单（旧 admin/view/cj/info.html）；新增时 {@code info} 为空行。{@code customize} 为解码后的自定义字段。
 */
public record CjInfoView(AdminHead head, LegacyLang lang, AdminRow info, List<Map<String, String>> customize, String showUrl)
        implements RawOutput {

    /** 旧 {@code $data['列'] eq '文字'}：新增时 {@code $data} 为 null，与非空文字比较为假。 */
    public boolean is(String col, String value) {
        return info.has(col) && info.s(col).equals(value);
    }

    /** 旧 {@code $data['列'] eq 数}（数值列，宽松比较；新增时 {@code null == 0} 为真）。 */
    public boolean eq(String col, long n) {
        return info.has(col) ? info.n(col) == n : n == 0;
    }

    /** 自定义字段一项的值放在属性值里时浏览器看到的值（旧 {@code {$vo.x}} 原样输出）。 */
    public String diyAttr(Map<String, String> vo, String key) {
        return attr(vo.get(key));
    }

    /** 自定义字段一项的值放在文本里（textarea）时浏览器看到的文字。 */
    public String diyText(Map<String, String> vo, String key) {
        return text(vo.get(key));
    }
}
