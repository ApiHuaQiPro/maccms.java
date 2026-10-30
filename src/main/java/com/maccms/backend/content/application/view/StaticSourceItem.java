package com.maccms.backend.content.application.view;

/**
 * 静态生成用的视频或文章（B16）：地址占位符要用的编号、拼音、时间与分类；视频带播放/下载的来源与地址（模式 3/4 逐集生成时数集数），
 * 文章带正文（按 {@code $$$} 分页数页数）。另一类的列为空串。
 */
public record StaticSourceItem(long id, String name, String en, long time, long typeId, String playFrom, String playUrl,
                               String downFrom, String downUrl, String content) {
    public StaticSourceItem {
        name = name == null ? "" : name;
        en = en == null ? "" : en;
        playFrom = playFrom == null ? "" : playFrom;
        playUrl = playUrl == null ? "" : playUrl;
        downFrom = downFrom == null ? "" : downFrom;
        downUrl = downUrl == null ? "" : downUrl;
        content = content == null ? "" : content;
    }
}
