package com.maccms.backend.comment.application.view;

import java.util.List;

/**
 * 页面上的一条评论（B11）。{@code name}、{@code content} 是浏览器看到的文字：库里的值经旧 {@code mac_restore_htmlfilter} 后，再按 HTML
 * 解码一次（旧模板原样输出、由浏览器解码），由页面模板转义输出。
 *
 * @param time    {@code comment_time}（Unix 秒）
 * @param replies 已审核的回复（旧版每条评论附带的 {@code sub}，Comment.php:46-54）
 */
public record CommentItem(long id, int mid, long rid, long userId, String name, long time, String content, long up,
                          long down, List<CommentItem> replies) {
}
