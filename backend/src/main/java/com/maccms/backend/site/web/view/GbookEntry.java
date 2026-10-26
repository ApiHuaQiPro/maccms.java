package com.maccms.backend.site.web.view;

/**
 * 留言列表中的一条（B11，{@code site/interaction/gbookList.jte}）：{@code text} 与 {@code reply} 是浏览器看到的纯文字（不是富文本）。
 *
 * @param reply     管理员回复（浏览器看到的文字）；空串表示没有回复
 * @param replyTime 回复时间；没有回复时为空串
 */
public record GbookEntry(long id, String name, String time, String text, String reply, String replyTime) {
}
