package com.maccms.backend.comment.application.view;

import java.util.List;

/**
 * 评论或留言列表的一页（旧 {@code listData} 的 page/pagecount/limit/total/list，Comment.php:94、Gbook.php:306）。
 *
 * @param page      当前页（1 起）
 * @param pageCount {@code ceil(total / limit)}
 */
public record BoardPage<T>(List<T> items, int page, long pageCount, int limit, long total) {
}
