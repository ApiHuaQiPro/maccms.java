package com.maccms.backend.member.application.view;

import java.util.List;

/**
 * 会员账号操作的结果（B09，18 文档第 3 节）：旧版 JSON 的 {@code code}、{@code msg}，以及本次响应要写出的 {@code Set-Cookie} 头值。
 *
 * @param code       旧版返回码（1 成功）
 * @param msg        旧版提示原文
 * @param setCookies 按顺序写出的 {@code Set-Cookie} 头值；没有时为空列表
 */
public record AccountReply(int code, String msg, List<String> setCookies) {

    public AccountReply {
        setCookies = List.copyOf(setCookies);
    }

    public static AccountReply of(int code, String msg) {
        return new AccountReply(code, msg, List.of());
    }

    public boolean ok() {
        return code == 1;
    }
}
