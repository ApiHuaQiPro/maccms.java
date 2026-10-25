package com.maccms.backend.admin.web.view;

import gg.jte.Content;

/**
 * 语言包里本身带 HTML 的文字（如 {@code admin/index/quickmenu/tip}、{@code admin/admin/popedom_tip} 里的 {@code <br>}）。旧模板用
 * {@code {:lang(...)}} 原样输出；新版只对这些固定的键原样输出——值来自打包进 jar 的仓库语言包（{@code common.lang.LegacyLang}），
 * 不含任何请求或库里的数据。AdminTemplatePolicyTest 检查模板只把语言包的取值交给这里。
 */
public final class AdminText {

    private AdminText() {
    }

    public static Content trusted(String langValue) {
        return output -> output.writeContent(langValue);
    }
}
