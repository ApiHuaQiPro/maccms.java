package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 评论列表中的一条（B11，{@code site/interaction/commentList.jte}）：显示名、时间、正文 {@code text}（浏览器看到的纯文字，模板转义输出，
 * 不是富文本）、顶踩数与顶、踩、举报三个表单，以及已审核的回复（回复不再带回复）。
 *
 * @param time 旧模板的 {@code {$vo.comment_time|mac_day}} 形式之一：{@code yyyy-MM-dd HH:mm:ss}（PRC）
 */
public record CommentEntry(long id, String name, String time, String text, long up, long down, InlineForm upForm,
                           InlineForm downForm, InlineForm reportForm, List<CommentEntry> replies) {

    public CommentEntry {
        replies = List.copyOf(replies);
    }
}
