package com.maccms.backend.comment.application.view;

/**
 * 发表评论/留言的结果：旧版返回的 {@code ['code' => …, 'msg' => …]}（ThinkPHP 按 ajax 请求编码为 JSON）。
 *
 * @param written 是否已写入一行（旧版此时写频率 cookie {@code comment_timespan}/{@code gbook_timespan}）
 */
public record PostReply(int code, String msg, boolean written) {

    public static PostReply of(int code, String msg) {
        return new PostReply(code, msg, false);
    }
}
