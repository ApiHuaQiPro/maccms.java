package com.maccms.backend.content.infrastructure;

/**
 * 播放/下载入口所需的 mac_vod 列（VodMapper.xml selectPlayback，B05）。密码列只以布尔表达式出现：是否非空、凭据是否与当前
 * 密码一致（SHA2），密码本身不读出。
 */
public record VodPlaybackRow(
        Long id,
        Integer categoryId,
        String name,
        String actor,
        String director,
        String classes,
        Integer copyright,
        Integer trySee,
        Integer pointsWhole,
        Integer pointsKind,
        String sourceFrom,
        String sourceUrl,
        String sourceServer,
        String sourceNote,
        Integer passwordRequired,
        Integer passwordProven,
        Long updatedAt) {

    @Override
    public String toString() {
        return "VodPlaybackRow[id=" + id + "]";
    }
}
