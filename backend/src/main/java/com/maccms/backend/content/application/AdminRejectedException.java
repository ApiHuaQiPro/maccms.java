package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;

/**
 * 后台内容管理拒绝一次操作（B13）：参数不合规等，由调用方以旧版的 {@code error()} 输出提示。不记录取值。
 */
public final class AdminRejectedException extends RuntimeException {

    private final transient AdminMessage reason;

    public AdminRejectedException(AdminMessage reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    /** "参数错误"（{@code lang('param_err')}）。 */
    public static AdminRejectedException paramError() {
        return new AdminRejectedException(AdminMessage.lang("param_err"));
    }

    public AdminMessage reason() {
        return reason;
    }
}
