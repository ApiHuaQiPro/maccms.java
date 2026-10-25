package com.maccms.backend.comment.application.view;

/**
 * 评论的顶踩数（旧 {@code comment/digg} 的 {@code data}：{@code comment_up}、{@code comment_down}，Comment.php:169-181；
 * {@code ajax/digg?mid=4} 的 {@code data}：{@code up}、{@code down}，Ajax.php:331-343）。
 *
 * @param found 按主键找到了这条评论（不看审核状态）；找不到时旧版为"获取失败"
 * @param rejected 写入被数据库拒绝（计数已到 mediumint 上限，旧版 500）
 */
public record CommentCounts(boolean found, boolean rejected, long up, long down) {

    public static final CommentCounts MISSING = new CommentCounts(false, false, 0, 0);
    public static final CommentCounts REJECTED = new CommentCounts(false, true, 0, 0);
}
