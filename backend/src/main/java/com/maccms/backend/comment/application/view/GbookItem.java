package com.maccms.backend.comment.application.view;

/**
 * 页面上的一条留言（B11）。文字同 {@link CommentItem} 的处理；{@code reply} 为空串表示还没有回复。
 *
 * @param time      {@code gbook_time}
 * @param replyTime {@code gbook_reply_time}（0 表示没有）
 */
public record GbookItem(long id, long rid, long userId, String name, long time, String content, String reply,
                        long replyTime) {
}
