package com.maccms.backend.admin.application.view;

/** 后台操作的结果：旧 {@code $this->success($msg)} / {@code $this->error($msg)}，{@code message} 已按语言包取好。 */
public record Reply(boolean ok, String message) {

    public static Reply success(String message) {
        return new Reply(true, message);
    }

    public static Reply error(String message) {
        return new Reply(false, message);
    }
}
