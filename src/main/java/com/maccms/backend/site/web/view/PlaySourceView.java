package com.maccms.backend.site.web.view;

import java.util.List;

/**
 * 一个播放源/下载源分组（B05）。
 *
 * @param key      分组标识（用于 id/aria 关联，形如 {@code source-2}，2 为旧 sid）
 * @param label    分组名称：播放器/下载器配置的 show，缺省时为来源编码
 * @param note     分组备注（vod_play_note 的该组，解码一次），可为空串
 * @param current  是否包含当前集
 * @param episodes 各集
 */
public record PlaySourceView(String key, String label, String note, boolean current, List<EpisodeLink> episodes) {
}
