package com.maccms.backend.content.application.view;

/**
 * 后台操作的结果（B13）：旧模型返回的 {@code ['code'=>1|10xx,'msg'=>…]}，控制器再以 {@code success/error} 输出。
 *
 * @param data 旧版 {@code success($msg, null, $data)} 的 data（如分类扩展字典）；没有时为 null
 */
public record AdminResult(boolean ok, AdminMessage message, Object data) {

    public static AdminResult ok(String langKey) {
        return new AdminResult(true, AdminMessage.lang(langKey), null);
    }

    public static AdminResult ok(AdminMessage message) {
        return new AdminResult(true, message, null);
    }

    public static AdminResult error(String langKey) {
        return new AdminResult(false, AdminMessage.lang(langKey), null);
    }

    public static AdminResult error(AdminMessage message) {
        return new AdminResult(false, message, null);
    }
}
