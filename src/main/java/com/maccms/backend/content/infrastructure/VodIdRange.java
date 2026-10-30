package com.maccms.backend.content.infrastructure;

/** mac_vod 全表（不带任何条件）的最小、最大 vod_id（VodMapper.selectIdRange）；空表时两者为 {@code null}。 */
public record VodIdRange(Long minId, Long maxId) {
}
