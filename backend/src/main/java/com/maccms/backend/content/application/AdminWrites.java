package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.infrastructure.AdminDataException;
import java.util.function.Supplier;

/** 后台内容操作的公共收尾：参数被拒绝、MySQL 拒绝取值时都按旧版的 {@code error()} 返回提示（22 文档 D-B13-3）。 */
final class AdminWrites {

    private AdminWrites() {
    }

    static AdminResult guard(Supplier<AdminResult> action) {
        try {
            return action.get();
        } catch (AdminRejectedException e) {
            return AdminResult.error(e.reason());
        } catch (AdminDataException e) {
            return AdminResult.error("param_err");
        }
    }
}
