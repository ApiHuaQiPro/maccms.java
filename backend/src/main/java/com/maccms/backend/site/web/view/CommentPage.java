package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 评论页（旧 {@code comment/index}，B11，{@code pages/comment.jte}）与评论片段（旧 {@code comment/ajax}，{@code ajax/comment.jte}，片段不用
 * {@code chrome}）。
 *
 * @param target 被评论的内容页（{@code mid}、{@code rid} 都给出且能生成地址时）
 * @param form   发表表单（可写数据源关闭时没有）
 */
public record CommentPage(PageChrome chrome, String heading, Optional<SafeUrl> target, BoardList<CommentEntry> list,
                          Optional<BoardPostForm> form) {
}
