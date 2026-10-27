package com.maccms.backend.admin.web;

import com.maccms.backend.content.application.PhpForm;

/** 测试用：让其他包的单元测试按后台控制器的方式把请求参数转成 {@link PhpForm}。 */
public final class AdminContentActionsAccess {

    private AdminContentActionsAccess() {
    }

    public static PhpForm form(AdminParams params) {
        return AdminContentActions.form(params);
    }
}
