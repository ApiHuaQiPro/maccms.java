package com.maccms.backend.comment.infrastructure;

/**
 * 评论列表的条件（旧 {@code Comment::listCacheData}，application/common/model/Comment.php:97-195）：只列已审核的；{@code pid} 为 0 时只列
 * 顶层评论，大于 0 时只列该评论的回复；{@code rid}、{@code mid}、{@code userId} 为 0 时不筛选。排序列只能是固定的几个（XML 中 choose）。
 *
 * @param sort       ID、TIME、UP、DOWN（旧 by=id/time/up/down）
 * @param descending 旧 order=desc
 */
public record CommentListCriteria(long rid, long pid, int mid, long userId, String sort, boolean descending) {
}
