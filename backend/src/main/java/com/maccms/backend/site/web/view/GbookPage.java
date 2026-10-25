package com.maccms.backend.site.web.view;

import java.util.Optional;

/**
 * 留言页（旧 {@code gbook/index}，B11，{@code pages/gbook.jte}）、留言片段（旧 {@code gbook/ajax}，{@code ajax/gbook.jte}）与报错页（旧
 * {@code gbook/report}，{@code pages/gbookReport.jte}：只有表单，{@code gbook_rid} 为报错的内容编号，正文预填 {@code subject}）。
 *
 * @param subject 报错页：旧参数 {@code name}（被报错的内容名称），其他页为空串
 */
public record GbookPage(PageChrome chrome, String heading, String subject, BoardList<GbookEntry> list,
                        Optional<BoardPostForm> form) {
}
