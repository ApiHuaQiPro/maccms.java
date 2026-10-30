package com.maccms.backend.install.web.view;

import gg.jte.Content;

/**
 * 语言包里本身带 HTML 的文字（安装协议 {@code install/user_agreement} 里的 {@code <br />}、{@code <strong>}）。旧模板用 {@code {:lang(...)}}
 * 原样输出；新版只对语言包的取值原样输出（值来自打包进 jar 的仓库语言包，不含请求或库里的数据），InstallTemplatePolicyTest 锁定参数形式。
 */
public final class InstallText {

    private InstallText() {
    }

    public static Content trusted(String langValue) {
        return output -> output.writeContent(langValue);
    }
}
